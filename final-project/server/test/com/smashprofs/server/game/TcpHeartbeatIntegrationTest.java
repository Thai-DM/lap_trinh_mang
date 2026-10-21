package com.smashprofs.server.game;

import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.*;
import com.smashprofs.server.room.RoomPhase;
import org.junit.*;
import java.net.Socket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** TCP thật, production maintenance/simulation Runnable, hai clock manual độc lập. */
public class TcpHeartbeatIntegrationTest {
    private final AtomicLong networkClock = new AtomicLong(), matchClock = new AtomicLong(10_000_000_000L);
    private final List<TcpTestClient> clients = new ArrayList<>();
    private ManualScheduledExecutor simulation, maintenance;
    private GameServer server;
    @Before public void start() {
        server = new GameServer(() -> {
            simulation = new ManualScheduledExecutor(); return new FixedTickScheduler(simulation, Logger.getLogger("heartbeat-tcp"));
        }, matchClock::get, HeartbeatConfiguration.DEFAULT, networkClock::get, () -> {
            maintenance = new ManualScheduledExecutor(); return maintenance;
        }); server.start();
    }
    @After public void stop() throws Exception { try { server.stop(); } finally { for (var client : clients) { client.close(); } } }
    private TcpTestClient client() throws Exception {
        var client = new TcpTestClient(server.getBoundPort()); clients.add(client); client.setAutoPong(false); client.connect();
        // ACK đã đọc không chứng minh activation sau send đã chạy; barrier stream chờ CONNECT trước hoàn tất.
        client.barrier(); return client;
    }
    private void seconds(long seconds) { networkClock.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); maintenance.runCycle(); }
    private ProtocolMessage ping(TcpTestClient client) throws Exception {
        var ping = client.awaitType(MessageType.PING); assertEquals(1, ping.version()); assertNotNull(ping.requestId());
        assertTrue(ping.payload().isEmpty()); return ping;
    }
    private void pong(TcpTestClient client, ProtocolMessage ping) throws Exception {
        client.send(new ProtocolMessage(MessageType.PONG, ping.requestId(), ProtocolV1.object())); client.barrier();
    }
    private void error(TcpTestClient client, String id, com.fasterxml.jackson.databind.node.ObjectNode payload, String code) throws Exception {
        client.send(new ProtocolMessage(MessageType.PONG, id, payload)); var error = client.awaitType(MessageType.ERROR);
        assertEquals(id, error.requestId()); assertEquals(code, error.payload().get("code").textValue());
    }
    private List<TcpTestClient> roster(String room, int count, boolean ready) throws Exception {
        var roster = new ArrayList<TcpTestClient>();
        for (int i = 0; i < count; i++) {
            var client = client(); roster.add(client);
            if (i == 0) { assertEquals(MessageType.ROOM_CREATED, client.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", room)).type()); }
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", room)).type());
            if (ready) { assertEquals(MessageType.ROOM_STATE, client.request(MessageType.PLAYER_READY, ProtocolV1.object().put("characterId", "Alex").put("ready", true)).type()); }
        }
        return roster;
    }
    private void launch(List<TcpTestClient> roster) throws Exception {
        simulation.runCycle(); matchClock.addAndGet(TimeUnit.SECONDS.toNanos(3)); simulation.runCycle();
        for (var client : roster) { client.awaitType(MessageType.GAME_START); }
    }
    private void states(List<TcpTestClient> roster, long tick) throws Exception {
        for (var client : roster) {
            var state = client.awaitType(MessageType.GAME_STATE); assertEquals(tick, state.payload().get("serverTick").longValue());
            for (var p : state.payload().get("players")) { assertEquals(100, p.get("hp").doubleValue(), 0.00001); assertFalse(p.get("dead").booleanValue()); }
        }
    }
    @Test(timeout=15000) public void heartbeatStartsOnlyAfterConnectAndPreservesClientPingCorrelation() throws Exception {
        try (var raw = new Socket("127.0.0.1", server.getBoundPort())) {
            raw.setSoTimeout(150); seconds(100); assertThrows(java.net.SocketTimeoutException.class, () -> raw.getInputStream().read());
        }
        var client = client(); seconds(4); client.assertQuiet(); seconds(1); var ping = ping(client); pong(client, ping);
        var reply = client.request(MessageType.PING, ProtocolV1.object()); assertEquals(MessageType.PONG, reply.type()); assertTrue(reply.payload().isEmpty());
        server.start(); assertEquals(1, maintenance.getScheduleCount()); client.assertQuiet();
    }
    @Test(timeout=15000) public void clientWithoutPongIsDisconnectedAtDeadline() throws Exception {
        var client = client(); seconds(5); ping(client); seconds(14); assertEquals(1, server.getConnectionManager().size());
        seconds(1); client.assertEof(); assertEquals(0, server.getConnectionManager().size()); seconds(50); assertTrue(server.isRunning());
    }
    @Test(timeout=15000) public void invalidDuplicateAndCrossSessionPongsCannotPreventTimeout() throws Exception {
        var a = client(); var b = client(); seconds(5); var pa = ping(a); var pb = ping(b);
        assertNotEquals(pa.requestId(), pb.requestId()); error(a, pb.requestId(), ProtocolV1.object(), "INVALID_PAYLOAD");
        error(a, pa.requestId(), ProtocolV1.object().put("playerId", b.playerId().value()), "UNAUTHORIZED_PLAYER");
        pong(b, pb); error(b, pb.requestId(), ProtocolV1.object(), "INVALID_PAYLOAD");
        seconds(15); a.assertEof(); pong(b, ping(b)); assertEquals(1, server.getConnectionManager().size()); b.assertQuiet();
    }
    @Test(timeout=20000) public void twoAndFourPlayerRoomsMaintainIndependentHeartbeatsWithoutInput() throws Exception {
        for (int count : new int[]{2, 4}) {
            var roster = roster("room" + count, count, false); seconds(5);
            // Các client cũ ở room2 cũng tiếp tục phản hồi; standing still không phải timeout.
            for (var client : clients) { pong(client, ping(client)); }
            assertEquals(clients.size(), server.getConnectionManager().size());
            for (var client : roster) { client.barrier(); client.assertQuiet(); }
        }
        assertEquals(6, server.getConnectionManager().size());
    }
    @Test(timeout=20000) public void lobbyTimeoutReleasesSlotCharacterAndReadyState() throws Exception {
        var roster = roster("lobby", 2, true); var room = server.getRoomManager().getRoom("lobby");
        assertEquals(RoomPhase.READY, room.getPhase()); seconds(5); ping(roster.get(0)); pong(roster.get(1), ping(roster.get(1)));
        seconds(15); roster.get(0).assertEof(); pong(roster.get(1), ping(roster.get(1)));
        assertEquals(1, room.getPlayerCount()); assertFalse(room.containsPlayer(roster.get(0).playerId()));
        assertFalse(room.isPlayerReady(roster.get(0).playerId())); assertNull(room.getSelectedCharacter(roster.get(0).playerId()));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        var newcomer = client(); assertEquals(MessageType.ROOM_STATE, newcomer.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "lobby")).type());
        assertEquals(0, room.getPlayerSlot(newcomer.playerId()).slotIndex()); assertFalse(room.isPlayerReady(newcomer.playerId()));
    }
    @Test(timeout=20000) public void countdownTimeoutCancelsWithoutFalseStart() throws Exception {
        var roster = roster("countdown", 2, true); var room = server.getRoomManager().getRoom("countdown");
        simulation.runCycle(); assertEquals(RoomPhase.COUNTDOWN, room.getPhase()); seconds(5);
        ping(roster.get(0)); pong(roster.get(1), ping(roster.get(1))); seconds(15); roster.get(0).assertEof();
        pong(roster.get(1), ping(roster.get(1))); matchClock.addAndGet(TimeUnit.SECONDS.toNanos(3)); simulation.runCycle();
        assertNull(room.getMatchRuntime()); assertEquals(RoomPhase.WAITING, room.getPhase()); roster.get(1).assertQuiet();
    }
    @Test(timeout=20000) public void playingTimeoutDoesNotStepPhysicsKillPlayerOrFinishEitherRoom() throws Exception {
        var a = roster("A4", 4, true); var b = roster("B2", 2, true); var everyone = new ArrayList<TcpTestClient>(a); everyone.addAll(b);
        launch(everyone); simulation.runCycle(); simulation.runCycle(); states(everyone, 3);
        var ma = server.getRoomManager().getRoom("A4").getMatchRuntime(); var mb = server.getRoomManager().getRoom("B2").getMatchRuntime();
        var sa = ma.getLatestSnapshot(); var sb = mb.getLatestSnapshot(); seconds(5);
        for (var client : everyone) { var ping = ping(client); if (client != a.get(0)) { pong(client, ping); } }
        seconds(15); a.get(0).assertEof(); assertEquals(5, server.getConnectionManager().size());
        for (var client : everyone) { if (client != a.get(0)) { pong(client, ping(client)); } }
        assertSame(sa, ma.getLatestSnapshot()); assertSame(sb, mb.getLatestSnapshot());
        assertEquals(3, ma.getMatchState().getServerTick()); assertEquals(3, mb.getMatchState().getServerTick());
        assertEquals(100, ma.getMatchState().getPlayer(a.get(0).playerId()).getHp(), 0.00001);
        assertFalse(ma.getMatchState().getPlayer(a.get(0).playerId()).isDead()); assertNull(ma.getMatchResult()); assertNull(mb.getMatchResult());
        assertEquals(4, server.getRoomManager().getRoom("A4").getPlayerCount());
        for (int i = 0; i < 3; i++) { simulation.runCycle(); }
        var remaining = new ArrayList<TcpTestClient>(a.subList(1, 4)); remaining.addAll(b); states(remaining, 6);
        for (var client : remaining) { client.barrier(); assertFalse(client.received().stream().anyMatch(m -> m.type() == MessageType.GAME_END)); client.assertQuiet(); }
        assertFalse(ma.isPhysicsDisposed()); assertFalse(mb.isPhysicsDisposed());
    }
    @Test(timeout=15000) public void stopAndRestartResetHeartbeatLifecycleAndOutstandingIds() throws Exception {
        var first = client(); seconds(5); var oldPing = ping(first); var oldExecutor = maintenance;
        server.stop(); first.assertEof(); assertTrue(oldExecutor.isTerminated()); oldExecutor.runCycle();
        server.start(); assertNotSame(oldExecutor, maintenance); assertEquals(1, maintenance.getScheduleCount());
        assertEquals(0, server.getConnectionManager().size()); var second = client(); seconds(5); var fresh = ping(second);
        assertNotEquals(oldPing.requestId(), fresh.requestId()); error(second, oldPing.requestId(), ProtocolV1.object(), "INVALID_PAYLOAD");
        pong(second, fresh); assertEquals(1, server.getConnectionManager().size());
    }
    @Test(timeout=15000) public void realMaintenanceWorkerStopsWithoutLeakingThreads() throws Exception {
        try (var realClientServer = new ServerClose(new GameServer("127.0.0.1", 0, 4, 20,
                new HeartbeatConfiguration(Duration.ofMillis(100), Duration.ofSeconds(5))))) {
            var real = realClientServer.server; real.start();
            try (var client = new TcpTestClient(real.getBoundPort())) {
                client.connect(); var ping = client.awaitType(MessageType.PING); pong(client, ping);
                var workers = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("smashprofs-heartbeat")).toList();
                assertFalse(workers.isEmpty()); real.stop();
                for (var worker : workers) { worker.join(5000); assertFalse(worker.isAlive()); }
            }
        }
    }
    private record ServerClose(GameServer server) implements AutoCloseable { @Override public void close() { server.stop(); } }
    @Test(timeout=20000) public void finishedTimeoutPreservesResultAndDoesNotSendSecondEnd() throws Exception {
        var roster = roster("finished", 2, true); launch(roster);
        matchClock.addAndGet(TimeUnit.SECONDS.toNanos(120)); simulation.runCycle();
        for (var client : roster) { client.awaitType(MessageType.GAME_STATE); client.awaitType(MessageType.GAME_END); }
        var room = server.getRoomManager().getRoom("finished"); var match = room.getMatchRuntime(); var result = match.getMatchResult();
        assertNotNull(result); seconds(5); ping(roster.get(0)); pong(roster.get(1), ping(roster.get(1)));
        seconds(15); roster.get(0).assertEof(); pong(roster.get(1), ping(roster.get(1))); simulation.runCycle();
        assertEquals(RoomPhase.FINISHED, room.getPhase()); assertEquals(2, room.getPlayerCount()); assertSame(result, match.getMatchResult());
        assertEquals(1, roster.get(1).received().stream().filter(m -> m.type() == MessageType.GAME_END).count()); roster.get(1).assertQuiet();
    }
    @Test(timeout=15000) public void realEofRacingTimeoutLeavesNoRegisteredSession() throws Exception {
        var client = client(); seconds(5); ping(client); networkClock.addAndGet(TimeUnit.SECONDS.toNanos(15));
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var eof = pool.submit(() -> { client.close(); return null; }); maintenance.runCycle(); eof.get(5, TimeUnit.SECONDS);
            assertEquals(0, server.getConnectionManager().size()); maintenance.runCycle(); assertTrue(server.isRunning());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
