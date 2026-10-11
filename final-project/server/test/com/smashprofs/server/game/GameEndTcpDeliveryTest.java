package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** TCP loopback thật, clock/executor manual; không sleep, không thay luật kết thúc trận. */
public class GameEndTcpDeliveryTest {
    private final ManualScheduledExecutor executor = new ManualScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final JsonFrameCodec codec = new JsonFrameCodec();
    private final List<Client> clients = new ArrayList<>();
    private GameServer server;
    @Before public void start() {
        server = new GameServer(() -> new FixedTickScheduler(executor, Logger.getLogger("end-test")), clock::get);
        server.start();
    }
    @After public void stop() throws Exception { try { server.stop(); } finally { for (var c : clients) { c.socket.close(); } } }
    private Client connect() throws Exception {
        var c = new Client(new Socket("127.0.0.1", server.getBoundPort())); c.socket.setSoTimeout(5000); clients.add(c);
        var ack = request(c, MessageType.CONNECT, ProtocolV1.object());
        c.id = new PlayerId(ack.payload().get("playerId").textValue()); return c;
    }
    private ProtocolMessage request(Client c, MessageType type, com.fasterxml.jackson.databind.node.ObjectNode payload) throws Exception {
        String id = "r" + c.sequence++;
        c.socket.getOutputStream().write(codec.encode(new ProtocolMessage(type, id, payload)));
        while (true) {
            var reply = codec.readNext(c.socket.getInputStream()); assertNotNull(reply);
            if (reply.type() == MessageType.GAME_START) { continue; }
            assertEquals(id, reply.requestId()); return reply;
        }
    }
    private List<Client> roster(String roomId, int count) throws Exception {
        var result = new ArrayList<Client>();
        for (int i = 0; i < count; i++) {
            var c = connect(); assertEquals(MessageType.ROOM_STATE, request(c, MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", roomId)).type());
            assertEquals(MessageType.ROOM_STATE, request(c, MessageType.PLAYER_READY, ProtocolV1.object().put("characterId", "Alex").put("ready", true)).type());
            result.add(c);
        }
        return result;
    }
    private void startMatches() throws Exception {
        executor.runCycle(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
        for (var c : clients) {
            var session = server.getConnectionManager().getSessions().stream().filter(s -> c.id.equals(s.getPlayerId())).findFirst().orElseThrow();
            if (session.getRoomId() != null && server.getRoomManager().getRoom(session.getRoomId()).getPhase() == RoomPhase.PLAYING) {
                assertEquals(MessageType.GAME_START, codec.readNext(c.socket.getInputStream()).type());
            }
        }
    }
    private MatchRuntime runtime(String room) { return server.getRoomManager().getRoom(room).getMatchRuntime(); }
    private void timeout() { clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle(); }
    private ProtocolMessage end(Client c, AuthoritativeMatchResult expected) throws Exception {
        var state = codec.readNext(c.socket.getInputStream()); assertNotNull(state); assertEquals(MessageType.GAME_STATE, state.type());
        assertEquals("FINISHED", state.payload().get("phase").textValue()); assertEquals(expected.finishedAtTick(), state.payload().get("serverTick").longValue());
        var end = codec.readNext(c.socket.getInputStream()); assertNotNull(end); assertEquals(MessageType.GAME_END, end.type());
        assertEquals(1, end.version()); assertNull(end.requestId());
        assertEquals(expected.matchId(), end.payload().get("matchId").textValue()); assertEquals(expected.roomId(), end.payload().get("roomId").textValue());
        assertEquals(expected.finishedAtTick(), end.payload().get("finishedAtTick").longValue());
        assertEquals(expected.finishReason().name(), end.payload().get("finishReason").textValue());
        assertEquals(expected.participants().size(), end.payload().get("participants").size());
        if (expected.isDraw()) { assertTrue(end.payload().get("winnerId").isNull()); }
        else { assertEquals(expected.winnerId().value(), end.payload().get("winnerId").textValue()); }
        for (var p : expected.participants()) {
            var wire = end.payload().get("participants").get(p.slotIndex());
            assertEquals(p.playerId().value(), wire.get("playerId").textValue()); assertEquals(p.slotIndex(), wire.get("slotIndex").intValue());
            assertEquals(p.characterId(), wire.get("characterId").textValue()); assertEquals(p.finalHp(), wire.get("finalHp").doubleValue(), 0.00001);
            assertEquals(p.eliminated(), wire.get("eliminated").booleanValue());
        }
        return end;
    }
    private void noMessage(Client c) throws Exception { c.socket.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> c.socket.getInputStream().read()); }
    private void everyone(int count) throws Exception {
        var roster = roster("arena", count); startMatches(); timeout(); var result = runtime("arena").getMatchResult();
        assertNotNull(result); assertEquals(RoomPhase.FINISHED, server.getRoomManager().getRoom("arena").getPhase());
        var first = end(roster.get(0), result); for (int i = 1; i < count; i++) { assertEquals(first, end(roster.get(i), result)); }
    }
    @Test public void twoClientsReceiveGameEnd() throws Exception { everyone(2); }
    @Test public void threeClientsReceiveGameEnd() throws Exception { everyone(3); }
    @Test public void fourClientsReceiveGameEnd() throws Exception { everyone(4); }
    @Test public void anotherRoomDoesNotReceiveGameEnd() throws Exception {
        var a = roster("a", 2); var b = roster("b", 2); startMatches();
        runtime("a").getMatchState().getPlayer(a.get(1).id).applyDamage(100); executor.runCycle();
        for (var c : a) { end(c, runtime("a").getMatchResult()); }
        assertNull(runtime("b").getMatchResult()); for (var c : b) { noMessage(c); }
    }
    @Test public void unjoinedClientDoesNotReceiveGameEnd() throws Exception {
        var outsider = connect(); var a = roster("arena", 2); startMatches(); timeout();
        for (var c : a) { end(c, runtime("arena").getMatchResult()); } noMessage(outsider);
    }
    @Test public void gameEndContainsCorrectWinner() throws Exception {
        var a = roster("arena", 4); startMatches();
        for (int i = 1; i < 4; i++) { runtime("arena").getMatchState().getPlayer(a.get(i).id).applyDamage(100); }
        executor.runCycle(); var result = runtime("arena").getMatchResult(); assertEquals(a.get(0).id, result.winnerId());
        assertEquals(AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING, result.finishReason());
        for (var c : a) { end(c, result); }
    }
    @Test public void simultaneousEliminationProducesCorrectOutcome() throws Exception {
        var a = roster("arena", 3); startMatches(); for (var c : a) { runtime("arena").getMatchState().getPlayer(c.id).applyDamage(100); }
        executor.runCycle(); var result = runtime("arena").getMatchResult(); assertTrue(result.isDraw());
        assertEquals(AuthoritativeMatchResult.FinishReason.ALL_PLAYERS_ELIMINATED, result.finishReason()); for (var c : a) { end(c, result); }
    }
    @Test public void timeoutWinnerPreservesFractionalHP() throws Exception {
        var a = roster("arena", 2); startMatches(); runtime("arena").getMatchState().getPlayer(a.get(1).id).applyDamage(0.25f);
        timeout(); var result = runtime("arena").getMatchResult(); assertEquals(a.get(0).id, result.winnerId()); for (var c : a) { end(c, result); }
    }
    @Test public void repeatedFinishedCallbacksDoNotDuplicateGameEndOrMutateResult() throws Exception {
        var a = roster("arena", 2); startMatches(); timeout(); var runtime = runtime("arena"); var result = runtime.getMatchResult();
        var snapshot = runtime.getLatestSnapshot(); for (var c : a) { end(c, result); }
        for (int i = 0; i < 20; i++) { executor.runCycle(); server.getRoomManager().getRoom("arena").getPhase(); }
        assertSame(result, runtime.getMatchResult()); assertSame(snapshot, runtime.getLatestSnapshot()); assertEquals(result.finishedAtTick(), runtime.getMatchState().getServerTick());
        for (var c : a) { noMessage(c); }
        server.stop(); assertSame(result, runtime.getMatchResult());
    }
    @Test public void disconnectDoesNotBlockOtherRecipients() throws Exception {
        var a = roster("arena", 3); startMatches(); a.get(0).socket.close(); timeout();
        for (int i = 1; i < 3; i++) { end(a.get(i), runtime("arena").getMatchResult()); }
        assertEquals(3, server.getRoomManager().getRoom("arena").getPlayerCount());
    }
    @Test public void clientCannotForceGameEnd() throws Exception {
        var a = roster("arena", 2); startMatches();
        var reply = request(a.get(0), MessageType.GAME_END, ProtocolV1.object()); assertEquals(MessageType.ERROR, reply.type());
        assertEquals("INVALID_PAYLOAD", reply.payload().get("code").textValue()); assertNull(runtime("arena").getMatchResult());
    }
    @Test public void gameEndMappingDoesNotStepPhysicsWorld() {
        var a = new PlayerId("a"); var b = new PlayerId("b"); var steps = new java.util.concurrent.atomic.AtomicInteger();
        var setup = new MatchSetup("native", "room", List.of(new MatchSetup.Participant(a, 0, "Alex"), new MatchSetup.Participant(b, 1, "Leo")));
        var runtime = new MatchRuntime(setup, new com.smashprofs.server.game.physics.PhysicsWorldFactory()::create,
                (world, dt, velocity, position) -> { steps.incrementAndGet(); world.step(dt, velocity, position); });
        try {
            runtime.start(); runtime.getMatchState().getPlayer(b).applyDamage(100); runtime.tick();
            var result = runtime.getMatchResult(); assertNotNull(result); assertEquals(1, steps.get());
            for (int i = 0; i < 50; i++) { com.smashprofs.server.network.GameEndMessageMapper.map(result); }
            assertEquals(1, steps.get()); assertSame(result, runtime.getMatchResult()); assertEquals(1, runtime.getMatchState().getServerTick());
        } finally { runtime.close(); }
    }
    @Test public void noEndDuringLobbyCountdownOrUnfinishedPlayingAndStop() throws Exception {
        var c = connect(); request(c, MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "lobby"));
        executor.runCycle(); noMessage(c);
        var a = roster("arena", 2); executor.runCycle(); for (var player : a) { noMessage(player); }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle(); assertNull(runtime("arena").getMatchResult());
        for (var player : a) { assertEquals(MessageType.GAME_START, codec.readNext(player.socket.getInputStream()).type()); }
        for (var player : a) { noMessage(player); } var unfinished = runtime("arena"); server.stop();
        assertNull(unfinished.getMatchResult()); for (var player : a) { assertEquals(-1, player.socket.getInputStream().read()); }
    }
    @Test(timeout=15000) public void concurrentControlFramesRemainValidAroundGameEnd() throws Exception {
        var a = roster("arena", 2); startMatches(); var c = a.get(0); var pool = Executors.newSingleThreadExecutor();
        try {
            var sends = pool.submit(() -> { try {
                for (int i = 0; i < 20; i++) { c.socket.getOutputStream().write(new JsonFrameCodec().encode(new ProtocolMessage(MessageType.CONNECT, "control" + i, ProtocolV1.object()))); }
            } catch (Exception e) { throw new RuntimeException(e); } });
            timeout(); sends.get(5, TimeUnit.SECONDS);
            int controls = 0, ends = 0; boolean terminalSeen = false; MessageType previous = null;
            for (int i = 0; i < 22; i++) {
                var frame = codec.readNext(c.socket.getInputStream()); assertNotNull(frame);
                if (frame.type() == MessageType.CONNECT_ACK) { controls++; }
                else if (frame.type() == MessageType.GAME_STATE) { assertFalse(terminalSeen); terminalSeen = true; }
                else { assertEquals(MessageType.GAME_END, frame.type()); assertTrue(terminalSeen); assertEquals(MessageType.GAME_STATE, previous); ends++; }
                previous = frame.type();
            }
            assertEquals(20, controls); assertEquals(1, ends); end(a.get(1), runtime("arena").getMatchResult());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static final class Client { final Socket socket; PlayerId id; int sequence; Client(Socket socket) { this.socket = socket; } }
}
