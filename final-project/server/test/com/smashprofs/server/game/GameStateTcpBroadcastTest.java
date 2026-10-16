package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.GameServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** TCP thật + executor/clock manual hiện có, simulation owner là test thread; không sleep. */
public class GameStateTcpBroadcastTest {
    private final ManualScheduledExecutor executor = new ManualScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final JsonFrameCodec codec = new JsonFrameCodec();
    private final List<Client> clients = new ArrayList<>();
    private GameServer server;
    @Before public void start() {
        server = new GameServer(() -> new FixedTickScheduler(executor, Logger.getLogger("broadcast-test")), clock::get); server.start();
    }
    @After public void stop() throws Exception { try { server.stop(); } finally { for (Client client : clients) { client.socket.close(); } } }
    private Client connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", server.getBoundPort()); socket.setSoTimeout(5000);
        Client client = new Client(socket); clients.add(client); request(client, MessageType.CONNECT, ProtocolV1.object()); return client;
    }
    private ProtocolMessage request(Client client, MessageType type, com.fasterxml.jackson.databind.node.ObjectNode payload) throws Exception {
        String requestId = "r" + client.request++;
        client.socket.getOutputStream().write(codec.encode(new ProtocolMessage(type, requestId, payload)));
        while (true) {
            var message = codec.readNext(client.socket.getInputStream()); assertNotNull(message);
            if (message.type() == MessageType.GAME_START) { recordStart(client); continue; }
            if (message.type() == MessageType.GAME_STATE) { client.states.add(message); continue; }
            assertEquals(requestId, message.requestId()); assertNotEquals(MessageType.ERROR, message.type());
            if (message.type() == MessageType.CONNECT_ACK) { client.id = new PlayerId(message.payload().get("playerId").textValue()); }
            return message;
        }
    }
    private List<Client> roster(String roomId, int count) throws Exception {
        var result = new ArrayList<Client>(); String[] characters = {"Viktor", "Maurice", "Leo", "Alex"};
        for (int i = 0; i < count; i++) {
            var client = connect(); request(client, MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", roomId));
            request(client, MessageType.PLAYER_READY, ProtocolV1.object().put("characterId", characters[i]).put("ready", true)); result.add(client);
        }
        return result;
    }
    private void startMatches() {
        executor.runCycle(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle(); // COUNTDOWN -> tick 1
    }
    private void cycles(int count) { for (int i = 0; i < count; i++) { executor.runCycle(); } }
    private ProtocolMessage state(Client client) throws Exception {
        if (!client.states.isEmpty()) { return client.states.remove(); }
        while (true) {
            var message = codec.readNext(client.socket.getInputStream()); assertNotNull(message);
            if (message.type() == MessageType.GAME_START) { recordStart(client); continue; }
            assertEquals(MessageType.GAME_STATE, message.type()); return message;
        }
    }
    private void recordStart(Client client) { assertFalse("Không duplicate GAME_START", client.startSeen); client.startSeen = true; }
    private void input(Client client, float axis, boolean ability) throws Exception {
        var payload = ProtocolV1.object().put("sequence", client.sequence++).put("clientTick", 0).put("moveAxis", axis)
                .put("jumpPressed", false).put("stompPressed", false).put("meleePressed", false).put("abilityPressed", ability).put("blockHeld", false);
        client.socket.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.PLAYER_INPUT, null, payload)));
        request(client, MessageType.CONNECT, ProtocolV1.object()); // same-stream queue barrier
    }
    private void sameSnapshot(int count) throws Exception {
        var players = roster("arena", count); startMatches(); cycles(2);
        var expected = state(players.get(0)); assertEquals(3, expected.payload().get("serverTick").longValue());
        assertEquals(count, expected.payload().get("players").size()); assertNull(expected.requestId());
        for (int i = 1; i < count; i++) { assertEquals(expected, state(players.get(i))); }
    }
    @Test public void twoClientsReceiveSameRoomSnapshot() throws Exception { sameSnapshot(2); }
    @Test public void threeClientsReceiveSameRoomSnapshot() throws Exception { sameSnapshot(3); }
    @Test public void fourClientsReceiveSameRoomSnapshot() throws Exception { sameSnapshot(4); }
    @Test public void simulationRemains60HzAndSnapshotsAreEveryThreeTicks() throws Exception {
        var roster = roster("arena", 2); startMatches(); cycles(2); assertEquals(3, state(roster.get(0)).payload().get("serverTick").longValue());
        state(roster.get(1)); assertEquals(TimeUnit.SECONDS.toNanos(1) / 60, executor.getPeriodNanos());
        cycles(3); assertEquals(6, state(roster.get(0)).payload().get("serverTick").longValue());
    }
    @Test public void clientsReceiveIncreasingServerTicks() throws Exception {
        var roster = roster("arena", 2); startMatches(); cycles(2); long first = state(roster.get(0)).payload().get("serverTick").longValue();
        state(roster.get(1)); cycles(3); assertTrue(state(roster.get(0)).payload().get("serverTick").longValue() > first);
    }
    @Test public void clientReceivesUpdatedPositionAfterMovement() throws Exception {
        var roster = roster("arena", 2); startMatches(); cycles(2); var before = state(roster.get(0)); state(roster.get(1));
        double x = before.payload().get("players").get(0).get("x").doubleValue(); input(roster.get(0), 1, false); cycles(3);
        var after = state(roster.get(0)); assertTrue(after.payload().get("players").get(0).get("x").doubleValue() > x);
    }
    @Test public void clientReceivesUpdatedFractionalHPAfterDamage() throws Exception {
        var roster = roster("arena", 2); startMatches(); cycles(2); state(roster.get(0)); state(roster.get(1));
        server.getRoomManager().getRoom("arena").getMatchRuntime().getMatchState().getPlayer(roster.get(0).id).applyDamage(7.25f);
        cycles(3); var after = state(roster.get(0)); assertEquals(92.75, after.payload().get("players").get(0).get("hp").doubleValue(), 0);
    }
    @Test public void clientReceivesCorrectActiveProjectileState() throws Exception {
        var roster = roster("arena", 2); startMatches(); cycles(2); state(roster.get(0)); state(roster.get(1));
        input(roster.get(0), 0, true); cycles(3); var after = state(roster.get(0));
        var runtime = server.getRoomManager().getRoom("arena").getMatchRuntime(); var views = runtime.getLatestSnapshot().projectiles();
        assertFalse(views.isEmpty()); assertEquals(views.size(), after.payload().get("projectiles").size());
        var view = views.get(0); var wire = after.payload().get("projectiles").get(0);
        assertEquals(view.projectileId(), wire.get("projectileId").longValue()); assertEquals(view.ownerPlayerId().value(), wire.get("ownerPlayerId").textValue());
        assertEquals(view.projectileType(), wire.get("projectileType").textValue()); assertEquals(view.x(), wire.get("x").doubleValue(), 0.00001);
    }
    @Test public void multipleRoomsBroadcastIndependentlyWithoutCrossRoomLeaks() throws Exception {
        var a = roster("roomA", 3); var b = roster("roomB", 2); startMatches(); cycles(2);
        for (var client : a) { var payload = state(client).payload(); assertEquals("roomA", payload.get("roomId").textValue()); assertEquals(3, payload.get("players").size()); }
        for (var client : b) { var payload = state(client).payload(); assertEquals("roomB", payload.get("roomId").textValue()); assertEquals(2, payload.get("players").size()); }
        cycles(3); assertEquals(6, state(a.get(0)).payload().get("serverTick").longValue()); assertEquals(6, state(b.get(0)).payload().get("serverTick").longValue());
    }
    @Test public void unjoinedClientDoesNotReceiveGameState() throws Exception {
        var unjoined = connect(); var a = roster("arena", 2); startMatches(); cycles(2); state(a.get(0)); state(a.get(1));
        unjoined.socket.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> unjoined.socket.getInputStream().read());
    }
    @Test public void disconnectedClientDoesNotBlockOtherClients() throws Exception {
        var a = roster("arena", 3); startMatches(); cycles(2); for (var c : a) { state(c); } a.get(0).socket.close();
        cycles(3); assertEquals(6, state(a.get(1)).payload().get("serverTick").longValue()); assertEquals(6, state(a.get(2)).payload().get("serverTick").longValue());
        assertEquals(3, server.getRoomManager().getRoom("arena").getPlayerCount()); // Không tự forfeit/remove gameplay.
    }
    @Test public void finalSnapshotIsSentOnceOutsideNormalCadenceBeforeGameEnd() throws Exception {
        var a = roster("arena", 2); startMatches(); cycles(2); for (var c : a) { state(c); }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); cycles(1);
        var terminal = state(a.get(0)); assertEquals(4, terminal.payload().get("serverTick").longValue());
        assertEquals("FINISHED", terminal.payload().get("phase").textValue()); assertEquals(0, terminal.payload().get("remainingTimeMs").longValue());
        assertEquals(terminal, state(a.get(1)));
        for (var client : a) { assertEquals(MessageType.GAME_END, codec.readNext(client.socket.getInputStream()).type()); }
        cycles(12); a.get(0).socket.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> a.get(0).socket.getInputStream().read());
    }
    @Test public void stoppingServerTerminatesBroadcastSafely() throws Exception {
        var a = roster("arena", 2); startMatches(); cycles(2); for (var c : a) { state(c); }
        server.stop(); assertEquals(0, server.getConnectionManager().size()); assertEquals(-1, a.get(0).socket.getInputStream().read());
        assertTrue(executor.isTerminated());
    }
    @Test public void restartCreatesFreshPublisherAndSessions() throws Exception {
        var realClock = new AtomicLong(10_000_000_000L);
        var restarted = new GameServer(FixedTickScheduler::new, realClock::get); restarted.start();
        var original = server; server = restarted;
        try {
            var old = roster("old", 2);
            var room = restarted.getRoomManager().getRoom("old");
            assertTrue(room.startCountdown() || room.isCountingDown()); realClock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            state(old.get(0)); state(old.get(1)); var oldRegistry = restarted.getConnectionManager();
            restarted.stop(); assertEquals(0, oldRegistry.size()); restarted.start();
            assertNotSame(oldRegistry, restarted.getConnectionManager()); assertEquals(0, restarted.getRoomManager().getRoomCount());
            var fresh = connect(); assertNotEquals(old.get(0).id, fresh.id); assertEquals(1, restarted.getConnectionManager().size());
            fresh.socket.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> fresh.socket.getInputStream().read());
        } finally { server = original; restarted.stop(); }
    }
    @Test public void realSchedulerAutomaticallyPublishesWithoutManualBroadcasterCalls() throws Exception {
        var realClock = new AtomicLong(10_000_000_000L);
        var realServer = new GameServer(FixedTickScheduler::new, realClock::get); realServer.start();
        try {
            var saved = server; server = realServer;
            try { var a = roster("auto", 2); assertTrue(realServer.getRoomManager().getRoom("auto").startCountdown()
                    || realServer.getRoomManager().getRoom("auto").isCountingDown()); realClock.addAndGet(TimeUnit.SECONDS.toNanos(3));
                assertEquals(MessageType.GAME_STATE, state(a.get(0)).type()); assertEquals(MessageType.GAME_STATE, state(a.get(1)).type());
            } finally { server = saved; }
        } finally { realServer.stop(); }
    }
    private static final class Client {
        final Socket socket; final Queue<ProtocolMessage> states = new ArrayDeque<>(); PlayerId id; int request; long sequence;
        boolean startSeen;
        Client(Socket socket) { this.socket = socket; }
    }
}
