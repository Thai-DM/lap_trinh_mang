package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.AuthoritativeGameSnapshot;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import com.smashprofs.server.game.MatchSetup;
import com.smashprofs.server.game.projectile.ServerProjectile;
import com.smashprofs.server.room.GameRoom;
import org.junit.After;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Backpressure trên socket thật, buffer nhỏ + latch quan sát native write; không sleep. */
public class TcpSnapshotBackpressureTest {
    private final List<Pair> pairs = new ArrayList<>();
    private final JsonFrameCodec codec = new JsonFrameCodec();
    @After public void cleanup() throws Exception { for (Pair pair : pairs) { pair.close(); } }
    private Pair pair() throws Exception {
        var pair = new Pair(); pairs.add(pair); return pair;
    }
    private GameStateDelivery delivery(long tick, boolean large) {
        var a = new PlayerId("a"); var b = new PlayerId("b");
        var players = List.of(new AuthoritativeGameSnapshot.PlayerSnapshot(a,0,"Viktor",1,2,0,0,99.5f,true,false,false,true),
                new AuthoritativeGameSnapshot.PlayerSnapshot(b,1,"Alex",3,4,0,0,100,true,false,false,true));
        List<ServerProjectile.View> projectiles = new ArrayList<>();
        if (large) { for (int i = 0; i < 2500; i++) { projectiles.add(new ServerProjectile.View(i,"m",a,"IBM",1,1,2,3,4)); } }
        return new GameStateDelivery(new AuthoritativeGameSnapshot("m","r",tick,players,projectiles), "PLAYING", 120000, () -> true);
    }
    private void blockWriter(Pair pair) throws Exception {
        assertTrue(pair.connection.offerGameState(delivery(3, true)));
        assertTrue(pair.observed.started.await(5, TimeUnit.SECONDS));
        assertFalse("Native write phải còn bị block khi peer chưa đọc", pair.observed.completed.await(100, TimeUnit.MILLISECONDS));
    }
    @Test(timeout=20000) public void reliableEventsSurviveSnapshotCoalescingAndPrecedeTerminalPair() throws Exception {
        var pair=pair();blockWriter(pair);
        var frame=new ReliableDelivery.Message(new ProtocolMessage(MessageType.MATCH_EVENT,null,ProtocolV1.object().put("sequence",1)));
        assertTrue(pair.connection.offerReliable(new ReliableDelivery(frame,()->true)));
        for(int tick=6;tick<=99;tick+=3) { pair.connection.offerGameState(delivery(tick,false)); }
        assertTrue(pair.connection.offerGameEnd(end(102)));
        assertEquals(MessageType.GAME_STATE,codec.readNext(pair.client.getInputStream()).type());
        var event=codec.readNext(pair.client.getInputStream());assertEquals(MessageType.MATCH_EVENT,event.type());assertEquals(1,event.payload().path("sequence").asInt());
        assertEquals(102,codec.readNext(pair.client.getInputStream()).payload().path("serverTick").asLong());
        assertEquals(MessageType.GAME_END,codec.readNext(pair.client.getInputStream()).type());
    }
    @Test(timeout=20000) public void reliableOverflowClosesOnlyTheSlowConnectionInsteadOfDroppingEvents() throws Exception {
        var slow=pair();var fast=pair();blockWriter(slow);
        var frame=new ReliableDelivery.Message(new ProtocolMessage(MessageType.MATCH_EVENT,null,ProtocolV1.object().put("sequence",1)));
        for(int i=0;i<128;i++) { assertTrue(slow.connection.offerReliable(new ReliableDelivery(frame,()->true))); }
        assertFalse(slow.connection.offerReliable(new ReliableDelivery(frame,()->true)));assertFalse(slow.connection.isOpen());assertTrue(fast.connection.isOpen());
        fast.connection.offerGameState(delivery(3,false));assertEquals(MessageType.GAME_STATE,codec.readNext(fast.client.getInputStream()).type());
    }
    @Test(timeout=20000) public void slowClientCannotCreateUnboundedQueue() throws Exception {
        var pair = pair(); blockWriter(pair);
        for (int tick = 6; tick <= 6000; tick += 3) {
            assertTrue(pair.connection.offerGameState(delivery(tick, false))); assertEquals(1, pair.connection.pendingSnapshotCount());
        }
        assertEquals(3, codec.readNext(pair.client.getInputStream()).payload().get("serverTick").longValue());
        assertEquals(6000, codec.readNext(pair.client.getInputStream()).payload().get("serverTick").longValue());
    }
    @Test(timeout=20000) public void slowClientDoesNotBlockNativeSimulationOrAnotherClient() throws Exception {
        var slow = pair(); var fast = pair(); blockWriter(slow);
        var clock = new AtomicLong(10_000_000_000L); var room = new GameRoom("native", clock::get);
        var a = new PlayerId("a"); var b = new PlayerId("b"); room.joinPlayer(a); room.joinPlayer(b);
        room.selectCharacter(a,"Alex"); room.selectCharacter(b,"Leo"); room.setReady(a,true); room.setReady(b,true);
        room.startCountdown(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); room.updateCountdown(); room.prepareMatch();
        var runtime = room.startMatch();
        try {
            for (int i = 0; i < 30; i++) { runtime.tick(); slow.connection.offerGameState(delivery(6 + i * 3, false)); }
            assertEquals(30, runtime.getMatchState().getServerTick()); assertEquals(1, slow.connection.pendingSnapshotCount());
            assertTrue(slow.connection.offerGameEnd(end(99)));
            runtime.tick(); assertEquals(31, runtime.getMatchState().getServerTick());
            assertEquals(1, slow.connection.pendingGameEndCount());
            assertFalse(slow.observed.completed.await(100, TimeUnit.MILLISECONDS));
            fast.connection.offerGameState(delivery(99,false));
            assertEquals(99, codec.readNext(fast.client.getInputStream()).payload().get("serverTick").longValue());
        } finally { runtime.close(); }
    }
    @Test(timeout=20000) public void controlMessagesAreNotDroppedOrInterleavedWithCoalescedSnapshots() throws Exception {
        var pair = pair(); blockWriter(pair); var controlStarted = new CountDownLatch(1);
        var controls = Executors.newSingleThreadExecutor();
        try {
            Future<?> control = controls.submit(() -> { controlStarted.countDown(); pair.connection.send(ProtocolV1.error("control","INVALID_PAYLOAD","test")); });
            assertTrue(controlStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> control.get(100, TimeUnit.MILLISECONDS));
            for (int tick = 6; tick <= 60; tick += 3) { pair.connection.offerGameState(delivery(tick,false)); }
            assertEquals(MessageType.GAME_STATE, codec.readNext(pair.client.getInputStream()).type());
            var response = codec.readNext(pair.client.getInputStream()); assertEquals(MessageType.ERROR,response.type()); assertEquals("control",response.requestId());
            assertEquals(60,codec.readNext(pair.client.getInputStream()).payload().get("serverTick").longValue()); control.get(5,TimeUnit.SECONDS);
        } finally { pair.connection.close(); controls.shutdownNow(); assertTrue(controls.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test(timeout=15000) public void shutdownUnblocksSlowWriteAndClearsOutboundQueue() throws Exception {
        var pair = pair(); blockWriter(pair); pair.connection.offerGameState(delivery(6,false)); pair.connection.close();
        assertEquals(0,pair.connection.pendingSnapshotCount()); assertFalse(pair.connection.offerGameState(delivery(9,false)));
        pair.writer.shutdown(); assertTrue(pair.writer.awaitTermination(5,TimeUnit.SECONDS)); assertEquals(0,pair.manager.size());
    }
    @Test public void invalidQueuedDeliveryIsDiscardedBeforeWriting() throws Exception {
        var pair = pair(); var delivery = delivery(3,false);
        var invalid = new GameStateDelivery(delivery.snapshot(),delivery.phase(),delivery.remainingTimeMs(),()->false);
        assertTrue(pair.connection.offerGameState(invalid)); pair.connection.discardInvalidGameState();
        pair.client.setSoTimeout(100); assertThrows(SocketTimeoutException.class,()->pair.client.getInputStream().read());
    }
    @Test public void oversizedGameStateClosesOnlyItsConnection() throws Exception {
        var pair = pair(); var fast = pair(); var sample = delivery(3,false);
        var projectiles = new ArrayList<ServerProjectile.View>();
        for (int i=0;i<10000;i++) { projectiles.add(new ServerProjectile.View(i,"m",new PlayerId("a"),"IBM",1,1,2,3,4)); }
        pair.connection.offerGameState(new GameStateDelivery(new AuthoritativeGameSnapshot("m","r",3,sample.snapshot().players(),projectiles),"PLAYING",120000,()->true));
        assertEquals(-1,pair.client.getInputStream().read()); assertFalse(pair.connection.isOpen());
        fast.connection.offerGameState(delivery(3,false)); assertEquals(MessageType.GAME_STATE,codec.readNext(fast.client.getInputStream()).type());
    }
    private GameEndDelivery end(long tick) {
        var state = delivery(tick, false);
        var result = new AuthoritativeMatchResult("m", "r", tick, new PlayerId("b"),
                AuthoritativeMatchResult.FinishReason.TIME_LIMIT, state.snapshot().players().stream().map(p ->
                new AuthoritativeMatchResult.ParticipantResult(p.playerId(), p.slotIndex(), p.characterId(), p.hp(), p.dead())).toList());
        return new GameEndDelivery(result, new GameStateDelivery(state.snapshot(), "FINISHED", 0, () -> true));
    }
    @Test(timeout=20000) public void terminalPairReplacesPendingOldSnapshotsWithoutOvertakingFinalState() throws Exception {
        var pair = pair(); blockWriter(pair);
        for (int tick = 6; tick <= 60; tick += 3) { pair.connection.offerGameState(delivery(tick, false)); }
        assertTrue(pair.connection.offerGameEnd(end(61))); assertEquals(0, pair.connection.pendingSnapshotCount());
        assertEquals(1, pair.connection.pendingGameEndCount()); assertFalse(pair.connection.offerGameEnd(end(61)));
        assertFalse(pair.connection.offerGameState(delivery(63, false)));
        assertEquals(3, codec.readNext(pair.client.getInputStream()).payload().get("serverTick").longValue());
        var last = codec.readNext(pair.client.getInputStream()); assertEquals(MessageType.GAME_STATE, last.type());
        assertEquals(61, last.payload().get("serverTick").longValue()); assertEquals("FINISHED", last.payload().get("phase").textValue());
        var result = codec.readNext(pair.client.getInputStream()); assertEquals(MessageType.GAME_END, result.type());
        assertEquals(61, result.payload().get("finishedAtTick").longValue());
        pair.client.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> pair.client.getInputStream().read());
    }
    @Test(timeout=20000) public void pendingEndDoesNotBlockSimulationOrAnotherClient() throws Exception {
        var slow = pair(); var fast = pair(); blockWriter(slow);
        assertTrue(slow.connection.offerGameEnd(end(4))); assertEquals(1, slow.connection.pendingGameEndCount());
        assertTrue(fast.connection.offerGameEnd(end(4)));
        assertEquals(MessageType.GAME_STATE, codec.readNext(fast.client.getInputStream()).type());
        assertEquals(MessageType.GAME_END, codec.readNext(fast.client.getInputStream()).type());
        assertFalse(slow.observed.completed.await(100, TimeUnit.MILLISECONDS));
    }
    @Test(timeout=15000) public void shutdownCleansUpPendingEndAndUnblocksWriter() throws Exception {
        var pair = pair(); blockWriter(pair); assertTrue(pair.connection.offerGameEnd(end(4)));
        pair.connection.close(); assertEquals(0, pair.connection.pendingGameEndCount());
        assertEquals(0, pair.connection.pendingSnapshotCount()); assertFalse(pair.connection.offerGameEnd(end(4)));
        pair.writer.shutdown(); assertTrue(pair.writer.awaitTermination(5, TimeUnit.SECONDS)); assertEquals(0, pair.manager.size());
    }
    @Test public void invalidEndIsNotWritten() throws Exception {
        var pair = pair(); var end = end(4); var finalState = end.finalState();
        assertTrue(pair.connection.offerGameEnd(new GameEndDelivery(end.result(),
                new GameStateDelivery(finalState.snapshot(), "FINISHED", 0, () -> false))));
        pair.connection.discardInvalidGameState(); pair.client.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> pair.client.getInputStream().read());
    }
    @Test(timeout=15000) public void terminalQueueOverflowClosesOnlyOverloadedConnection() throws Exception {
        var slow = pair(); var fast = pair(); blockWriter(slow);
        for (int i = 0; i < 4; i++) {
            var sample = end(4); String match = "m" + i;
            var result = new AuthoritativeMatchResult(match, "r", 4, sample.result().winnerId(),
                    sample.result().finishReason(), sample.result().participants());
            var snapshot = sample.finalState().snapshot();
            assertTrue(slow.connection.offerGameEnd(new GameEndDelivery(result,
                    new GameStateDelivery(new AuthoritativeGameSnapshot(match, "r", 4, snapshot.players(), List.of()), "FINISHED", 0, () -> true))));
        }
        assertFalse(slow.connection.offerGameEnd(end(4))); assertFalse(slow.connection.isOpen());
        assertEquals(0, slow.connection.pendingGameEndCount()); fast.connection.offerGameEnd(end(4));
        assertEquals(MessageType.GAME_STATE, codec.readNext(fast.client.getInputStream()).type());
        assertEquals(MessageType.GAME_END, codec.readNext(fast.client.getInputStream()).type());
    }
    private GameStartDelivery startDelivery() {
        return new GameStartDelivery(new MatchSetup("m", "r", List.of(new MatchSetup.Participant(new PlayerId("a"), 0, "Viktor"),
                new MatchSetup.Participant(new PlayerId("b"), 1, "Alex"))), () -> true);
    }
    @Test(timeout=20000) public void queuedStartPrecedesLatestSnapshotAndTerminalWhileWriterIsBlocked() throws Exception {
        var pair = pair(); var sample = delivery(3, true); var old = sample.snapshot();
        var oldProjectiles = old.projectiles().stream().map(p -> new ServerProjectile.View(p.projectileId(), "previous", p.ownerPlayerId(),
                p.projectileType(), p.spawnTick(), p.x(), p.y(), p.velocityX(), p.velocityY())).toList();
        pair.connection.offerGameState(new GameStateDelivery(new AuthoritativeGameSnapshot("previous", "r", 3, old.players(), oldProjectiles),
                "PLAYING", 120000, () -> true));
        assertTrue(pair.observed.started.await(5, TimeUnit.SECONDS)); assertFalse(pair.observed.completed.await(100, TimeUnit.MILLISECONDS));
        assertTrue(pair.connection.offerGameStart(startDelivery())); assertFalse(pair.connection.offerGameStart(startDelivery()));
        assertEquals(1, pair.connection.pendingGameStartCount()); pair.connection.offerGameState(delivery(6, false));
        pair.connection.offerGameEnd(end(9));
        assertEquals("previous", codec.readNext(pair.client.getInputStream()).payload().get("matchId").textValue()); // Không cắt frame đang ghi.
        assertEquals(MessageType.GAME_START, codec.readNext(pair.client.getInputStream()).type());
        assertEquals("FINISHED", codec.readNext(pair.client.getInputStream()).payload().get("phase").textValue());
        assertEquals(MessageType.GAME_END, codec.readNext(pair.client.getInputStream()).type());
    }
    @Test(timeout=15000) public void shutdownClearsPendingStartAndUnblocksWriter() throws Exception {
        var pair = pair(); blockWriter(pair); assertTrue(pair.connection.offerGameStart(startDelivery()));
        assertEquals(1, pair.connection.pendingGameStartCount()); pair.connection.close();
        assertEquals(0, pair.connection.pendingGameStartCount()); assertFalse(pair.connection.offerGameStart(startDelivery()));
        pair.writer.shutdown(); assertTrue(pair.writer.awaitTermination(5, TimeUnit.SECONDS));
    }
    private HeartbeatDelivery heartbeat(String id) {
        return new HeartbeatDelivery(new ProtocolMessage(MessageType.PING, id, ProtocolV1.object()), () -> true);
    }
    @Test(timeout=20000) public void pendingHeartbeatCannotBlockOtherClientOrSplitTerminalPair() throws Exception {
        var slow = pair(); var fast = pair(); blockWriter(slow);
        assertTrue(slow.connection.offerHeartbeat(heartbeat("slow"))); assertEquals(1, slow.connection.pendingHeartbeatCount());
        assertTrue(fast.connection.offerHeartbeat(heartbeat("fast")));
        assertEquals("fast", codec.readNext(fast.client.getInputStream()).requestId());
        assertTrue(slow.connection.offerGameEnd(end(4)));
        assertEquals(MessageType.GAME_STATE, codec.readNext(slow.client.getInputStream()).type());
        assertEquals("FINISHED", codec.readNext(slow.client.getInputStream()).payload().get("phase").textValue());
        assertEquals(MessageType.GAME_END, codec.readNext(slow.client.getInputStream()).type());
        assertEquals("slow", codec.readNext(slow.client.getInputStream()).requestId());
    }
    @Test(timeout=15000) public void heartbeatQueueOverflowClosesOnlyOverloadedPeer() throws Exception {
        var slow = pair(); var fast = pair(); blockWriter(slow);
        assertTrue(slow.connection.offerHeartbeat(heartbeat("one")));
        assertFalse(slow.connection.offerHeartbeat(heartbeat("two"))); assertFalse(slow.connection.isOpen());
        assertEquals(0, slow.connection.pendingHeartbeatCount());
        assertTrue(fast.connection.offerHeartbeat(heartbeat("fast"))); assertEquals(MessageType.PING, codec.readNext(fast.client.getInputStream()).type());
        slow.writer.shutdown(); assertTrue(slow.writer.awaitTermination(5, TimeUnit.SECONDS));
    }
    @Test public void invalidHeartbeatGuardDropsPingBeforeSocketWrite() throws Exception {
        var pair = pair(); assertTrue(pair.connection.offerHeartbeat(new HeartbeatDelivery(heartbeat("invalid").message(), () -> false)));
        pair.client.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> pair.client.getInputStream().read());
    }
    private static final class Pair implements AutoCloseable {
        final Socket client; final ObservedSocket observed; final TcpClientConnection connection;
        final ExecutorService writer = Executors.newSingleThreadExecutor(); final ConnectionManager manager = new ConnectionManager();
        Pair() throws Exception {
            try (ServerSocket listener=new ServerSocket()) {
                listener.bind(new InetSocketAddress("127.0.0.1",0)); client=new Socket(); client.setReceiveBufferSize(1024);
                client.connect(new InetSocketAddress("127.0.0.1",listener.getLocalPort())); client.setSoTimeout(5000);
                Socket accepted=listener.accept(); accepted.setSendBufferSize(1024); observed=new ObservedSocket(accepted);
            }
            connection=new TcpClientConnection(observed,UUID.randomUUID().toString(),manager,
                    new TcpIoConfiguration(JsonTcpMessageCodec::new,new MessageDispatcher((s,t,p)->{}),ProtocolV1.MAX_FRAME_BYTES,5000),s->{});
            manager.register(connection.getSession()); connection.startWriting(writer);
        }
        @Override public void close() throws Exception { connection.close(); client.close(); writer.shutdown(); assertTrue(writer.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    /** Chỉ quan sát native write, không fake độ chậm, bytes hoặc việc unblock. */
    private static final class ObservedSocket extends Socket {
        final Socket delegate; final CountDownLatch started=new CountDownLatch(1), completed=new CountDownLatch(1);
        ObservedSocket(Socket delegate) { this.delegate=delegate; }
        @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException {
            var output=delegate.getOutputStream();
            return new OutputStream() {
                @Override public void write(int b)throws IOException { output.write(b); }
                @Override public void write(byte[] b,int off,int len)throws IOException {
                    if(len>4096) { started.countDown(); }
                    try { output.write(b,off,len); } finally { if(len>4096) { completed.countDown(); } }
                }
                @Override public void flush()throws IOException { output.flush(); }
            };
        }
        @Override public synchronized void setSoTimeout(int timeout)throws SocketException { delegate.setSoTimeout(timeout); }
        @Override public synchronized void close()throws IOException { delegate.close(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
    }
}
