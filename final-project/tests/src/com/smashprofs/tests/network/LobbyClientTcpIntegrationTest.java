package com.smashprofs.tests.network;

import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.network.HeartbeatConfiguration;
import org.junit.After;
import org.junit.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Production GameServer, real sockets/clock/countdown; no MockServer or Thread.sleep. */
public class LobbyClientTcpIntegrationTest {
    private GameServer server;
    private final List<Probe> probes = new ArrayList<>();
    private CountingClient outsider;
    private final ScheduledExecutorService conditions = Executors.newSingleThreadScheduledExecutor();

    @After public void cleanup() throws Exception {
        try {
            for (var probe : probes) { probe.network.allowJoin.countDown(); probe.lobby.closeAndWait(5000); }
            if (outsider != null) { outsider.disconnectAndWait(3000); }
        } finally {
            try { if (server != null) { server.stop(); } }
            finally { conditions.shutdownNow(); assertTrue(conditions.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }

    private void startServer(boolean fastHeartbeat) {
        server = fastHeartbeat ? new GameServer("127.0.0.1", 0, 8, 20,
                new HeartbeatConfiguration(Duration.ofMillis(100), Duration.ofMillis(600)))
                : new GameServer("127.0.0.1", 0, 8);
        server.start();
    }

    private Probe start(String room, LobbyConfiguration.Mode mode, boolean holdJoin) {
        var network = new CountingClient(server.getBoundPort(), holdJoin);
        var probe = new Probe(new LobbyConfiguration("127.0.0.1", server.getBoundPort(), room, mode), network);
        probes.add(probe);
        assertTrue(probe.lobby.startAsync());
        assertFalse("Repeated start must not create a new connection", probe.lobby.startAsync());
        return probe;
    }

    private Probe joined(String room, LobbyConfiguration.Mode mode) throws Exception {
        var probe = start(room, mode, false);
        probe.await(LobbyClient.Phase.JOINED);
        return probe;
    }

    private void awaitCondition(BooleanSupplier condition) throws Exception {
        var complete = new CompletableFuture<Void>();
        var task = conditions.scheduleAtFixedRate(() -> {
            try { if (condition.getAsBoolean()) { complete.complete(null); } }
            catch (Throwable failure) { complete.completeExceptionally(failure); }
        }, 0, 10, TimeUnit.MILLISECONDS);
        try { complete.get(3, TimeUnit.SECONDS); } finally { task.cancel(false); }
    }

    private void assertNoStart(Probe probe, long milliseconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { break; }
            var state = probe.events.poll(remaining, TimeUnit.NANOSECONDS);
            if (state == null) { break; }
            assertFalse("No fabricated match start", state.isMatchStarted());
        }
        assertNull(probe.lobby.getSnapshot().match());
        assertEquals(0, probe.network.countReceived(MessageType.GAME_START));
    }

    private void checkRequests(Probe probe, boolean creator) {
        assertEquals(creator ? 1 : 0, probe.network.countSent(MessageType.CREATE_ROOM));
        assertEquals(1, probe.network.countSent(MessageType.JOIN_ROOM));
        assertEquals(1, probe.network.countSent(MessageType.PLAYER_READY));
        assertEquals(0, probe.network.countSent(MessageType.PLAYER_INPUT));
        var requests = probe.network.sent;
        assertEquals(requests.size(), requests.stream().map(ProtocolMessage::requestId).distinct().count());
        for (var request : requests) {
            assertEquals(1, request.version());
            assertNotNull(request.requestId());
            assertEquals(UUID.fromString(request.requestId()).toString(), request.requestId());
            assertFalse(request.payload().has("playerId"));
            assertFalse(request.payload().has("sessionId"));
        }
    }

    @Test(timeout=25000) public void hostCreatesThenExplicitlyJoinsAndBothClientsReceiveAuthoritativeStart() throws Exception {
        startServer(false);
        var host = start("two-client-room", LobbyConfiguration.Mode.CREATE, true);
        assertTrue(host.network.joinOffered.await(3, TimeUnit.SECONDS));
        try {
            var created = server.getRoomManager().getRoom("two-client-room");
            assertNotNull(created);
            assertEquals("ROOM_CREATED must not imply host membership", 0, created.getPlayerCount());
            assertNull(created.getMatchRuntime());
        } finally { host.network.allowJoin.countDown(); }
        var hostJoined = host.await(LobbyClient.Phase.JOINED);
        assertEquals("LobbyClient-Connect", host.network.connectThreadName);
        var joiner = joined("two-client-room", LobbyConfiguration.Mode.JOIN);
        var joinState = joiner.lobby.getSnapshot();
        assertNotEquals(hostJoined.localPlayerId(), joinState.localPlayerId());
        assertEquals(2, joinState.members().size());
        assertTrue(joinState.members().stream().allMatch(p -> !p.ready() && p.characterId() == null));
        assertEquals(Set.of(hostJoined.localPlayerId(), joinState.localPlayerId()),
                new HashSet<>(joinState.members().stream().map(LobbyClient.Member::playerId).toList()));

        outsider = new CountingClient(server.getBoundPort(), false);
        outsider.connect();
        assertTrue(outsider.connected.await(3, TimeUnit.SECONDS));
        assertTrue(host.lobby.ready("Alex"));
        assertFalse(host.lobby.ready("Alex"));
        host.await(LobbyClient.Phase.WAITING_FOR_START);
        long readySentAt = System.nanoTime();
        assertTrue(joiner.lobby.ready("Leo"));
        assertFalse(joiner.lobby.ready("Leo"));
        joiner.await(LobbyClient.Phase.WAITING_FOR_START);
        assertFalse(host.lobby.getSnapshot().isMatchStarted());
        assertFalse(joiner.lobby.getSnapshot().isMatchStarted());

        var a = host.await(LobbyClient.Phase.STARTED);
        var b = joiner.await(LobbyClient.Phase.STARTED);
        assertTrue("Server must perform the full three-second countdown",
                System.nanoTime() - readySentAt >= TimeUnit.SECONDS.toNanos(3));
        assertEquals(a.match(), b.match());
        assertEquals("two-client-room", a.match().roomId());
        assertEquals(server.getRoomManager().getRoom("two-client-room").getMatchRuntime().getMatchId(), a.match().matchId());
        assertEquals(0, a.match().localPlayer(a.localPlayerId()).slotIndex());
        assertEquals(1, b.match().localPlayer(b.localPlayerId()).slotIndex());
        assertEquals("Alex", a.match().localPlayer(a.localPlayerId()).characterId());
        assertEquals("Leo", b.match().localPlayer(b.localPlayerId()).characterId());
        assertTrue(outsider.isConnected());
        assertEquals(0, outsider.countReceived(MessageType.GAME_START));

        // Repeat callbacks and already-completed responses: no new requests or match publication.
        var original = host.lobby.getSnapshot();
        host.lobby.onConnected();
        host.lobby.onProtocolMessageReceived(host.network.firstReceived(MessageType.ROOM_CREATED));
        host.lobby.onProtocolMessageReceived(host.network.firstReceived(MessageType.ROOM_STATE));
        host.lobby.onProtocolMessageReceived(host.network.firstReceived(MessageType.GAME_START));
        assertSame(original, host.lobby.getSnapshot());
        assertFalse(host.lobby.ready("Viktor"));
        assertFalse(host.lobby.startAsync());
        checkRequests(host, true);
        checkRequests(joiner, false);
        assertEquals(List.of(MessageType.CREATE_ROOM, MessageType.JOIN_ROOM, MessageType.PLAYER_READY),
                host.network.sent.stream().map(ProtocolMessage::type).toList());
        assertEquals(1, host.starts());
        assertEquals(1, joiner.starts());
        assertEquals(1, host.network.countReceived(MessageType.GAME_START));
        assertEquals(1, joiner.network.countReceived(MessageType.GAME_START));
    }

    @Test(timeout=25000) public void fourPlayerRosterMapsEveryLocalIdentityIncludingNonzeroSlots() throws Exception {
        startServer(false);
        List<Probe> roster = new ArrayList<>();
        roster.add(joined("four-client-room", LobbyConfiguration.Mode.CREATE));
        for (int i = 1; i < 4; i++) { roster.add(joined("four-client-room", LobbyConfiguration.Mode.JOIN)); }
        assertEquals(4, roster.get(3).lobby.getSnapshot().members().size());
        String[] characters = {"Alex", "Luca", "Maurice", "Viktor"};
        for (int i = 0; i < roster.size(); i++) {
            assertTrue(roster.get(i).lobby.ready(characters[i]));
            roster.get(i).await(LobbyClient.Phase.WAITING_FOR_START);
        }
        GameStartRoster common = null;
        for (int i = 0; i < roster.size(); i++) {
            var probe = roster.get(i);
            var started = probe.await(LobbyClient.Phase.STARTED);
            assertEquals(4, started.match().players().size());
            var local = started.match().localPlayer(started.localPlayerId());
            assertEquals(i, local.slotIndex());
            assertEquals(characters[i], local.characterId());
            if (common == null) { common = started.match(); } else { assertEquals(common, started.match()); }
            checkRequests(probe, i == 0);
            assertEquals(1, probe.starts());
            assertThrows(UnsupportedOperationException.class, () -> started.match().players().clear());
        }
    }

    @Test(timeout=15000) public void heartbeatWorksWhileReadyClientWaitsAloneInLobby() throws Exception {
        startServer(true);
        var host = joined("idle-lobby", LobbyConfiguration.Mode.CREATE);
        assertTrue(host.lobby.ready("Alex"));
        host.await(LobbyClient.Phase.WAITING_FOR_START);
        assertTrue(host.network.pings.await(6, TimeUnit.SECONDS));
        assertTrue(host.network.isConnected());
        assertEquals(1, server.getConnectionManager().size());
        assertEquals(0, host.network.countReceived(MessageType.ERROR));
        assertEquals(0, host.network.countReceived(MessageType.GAME_START));
        assertEquals(LobbyClient.Phase.WAITING_FOR_START, host.lobby.getSnapshot().phase());
        assertNull(server.getRoomManager().getRoom("idle-lobby").getMatchRuntime());
        checkRequests(host, true);
    }

    @Test(timeout=15000) public void duplicateRoomCreationShowsErrorWithoutImplicitJoinOrRetry() throws Exception {
        startServer(false);
        server.getRoomManager().createRoom("existing-room");
        var host = start("existing-room", LobbyConfiguration.Mode.CREATE, false);
        var error = host.await(LobbyClient.Phase.ERROR);
        assertEquals("INVALID_ROOM_STATE", error.errorCode());
        assertTrue(error.statusText().contains("INVALID_ROOM_STATE"));
        assertNotNull(error.localPlayerId());
        assertTrue(error.members().isEmpty());
        assertFalse(host.lobby.ready("Alex"));
        host.lobby.onConnected();
        assertFalse(host.lobby.startAsync());
        assertEquals(1, host.network.countSent(MessageType.CREATE_ROOM));
        assertEquals(0, host.network.countSent(MessageType.JOIN_ROOM));
        assertEquals(0, server.getRoomManager().getRoom("existing-room").getPlayerCount());
    }

    @Test(timeout=15000) public void fifthJoinerShowsRoomFullWithoutReadyOrInventedMembership() throws Exception {
        startServer(false);
        joined("full-room", LobbyConfiguration.Mode.CREATE);
        for (int i = 1; i < 4; i++) { joined("full-room", LobbyConfiguration.Mode.JOIN); }
        var fifth = start("full-room", LobbyConfiguration.Mode.JOIN, false);
        var error = fifth.await(LobbyClient.Phase.ERROR);
        assertEquals("ROOM_FULL", error.errorCode());
        assertTrue(error.members().isEmpty());
        assertNull(error.match());
        assertEquals(4, server.getRoomManager().getRoom("full-room").getPlayerCount());
        assertEquals(0, fifth.network.countSent(MessageType.CREATE_ROOM));
        assertEquals(1, fifth.network.countSent(MessageType.JOIN_ROOM));
        assertEquals(0, fifth.network.countSent(MessageType.PLAYER_READY));
    }

    @Test(timeout=15000) public void readyRejectionDoesNotClaimReadyOrStartAndRetainsConfirmedSelection() throws Exception {
        startServer(false);
        var host = joined("removed-room", LobbyConfiguration.Mode.CREATE);
        assertFalse(host.lobby.ready("UnknownCharacter"));
        assertEquals("INVALID_CHARACTER", host.lobby.getSnapshot().errorCode());
        assertEquals(0, host.network.countSent(MessageType.PLAYER_READY));
        // Consume the local validation event before checking the server's READY rejection.
        host.await(LobbyClient.Phase.JOINED);
        server.getRoomManager().removeRoom("removed-room");
        assertTrue(host.lobby.ready("Leo"));
        var rejected = host.await(LobbyClient.Phase.JOINED);
        assertEquals("ROOM_NOT_FOUND", rejected.errorCode());
        assertNull(rejected.selectedCharacter());
        assertTrue(rejected.members().stream().noneMatch(LobbyClient.Member::ready));
        assertNull(rejected.match());
        assertFalse(rejected.isMatchStarted());
        assertEquals(1, host.network.countSent(MessageType.PLAYER_READY));
    }

    @Test(timeout=20000) public void disconnectBeforeReadyCannotStartASingleSurvivor() throws Exception {
        startServer(false);
        var host = joined("disconnect-before-ready", LobbyConfiguration.Mode.CREATE);
        var joiner = joined("disconnect-before-ready", LobbyConfiguration.Mode.JOIN);
        host.lobby.closeAndWait(3000);
        awaitCondition(() -> server.getRoomManager().getRoom("disconnect-before-ready").getPlayerCount() == 1);
        assertTrue(joiner.lobby.ready("Leo"));
        joiner.await(LobbyClient.Phase.WAITING_FOR_START);
        assertNoStart(joiner, 3300);
        assertNull(server.getRoomManager().getRoom("disconnect-before-ready").getMatchRuntime());
        assertNull(host.lobby.getSnapshot().match());
        assertFalse(host.lobby.getSnapshot().isMatchStarted());
    }

    @Test(timeout=20000) public void disconnectDuringRealCountdownCancelsStart() throws Exception {
        startServer(false);
        var host = joined("disconnect-countdown", LobbyConfiguration.Mode.CREATE);
        var joiner = joined("disconnect-countdown", LobbyConfiguration.Mode.JOIN);
        assertTrue(host.lobby.ready("Alex")); host.await(LobbyClient.Phase.WAITING_FOR_START);
        assertTrue(joiner.lobby.ready("Leo")); joiner.await(LobbyClient.Phase.WAITING_FOR_START);
        var room = server.getRoomManager().getRoom("disconnect-countdown");
        awaitCondition(room::isCountingDown);
        assertFalse(host.lobby.getSnapshot().isMatchStarted());
        host.lobby.closeAndWait(3000);
        awaitCondition(() -> room.getPlayerCount() == 1 && !room.isCountingDown());
        var latePayload = ProtocolV1.object().put("matchId", "late-match").put("roomId", "disconnect-countdown");
        var latePlayers = latePayload.putArray("players");
        for (var member : joiner.lobby.getSnapshot().members()) {
            latePlayers.addObject().put("playerId", member.playerId().value()).put("slotIndex", member.slotIndex())
                    .put("characterId", member.characterId());
        }
        var closedState = host.lobby.getSnapshot();
        host.lobby.onProtocolMessageReceived(new ProtocolMessage(MessageType.GAME_START, null, latePayload));
        assertSame("A late callback cannot start a disconnected client", closedState, host.lobby.getSnapshot());
        assertNoStart(joiner, 3300);
        assertNull(room.getMatchRuntime());
        assertEquals(0, host.network.countReceived(MessageType.GAME_START));
        assertNull(host.lobby.getSnapshot().match());
    }

    private static final class Probe {
        final CountingClient network;
        final LobbyClient lobby;
        final BlockingQueue<LobbyClient.Snapshot> events = new LinkedBlockingQueue<>();
        final List<LobbyClient.Snapshot> history = new CopyOnWriteArrayList<>();
        Probe(LobbyConfiguration configuration, CountingClient network) {
            this.network = network;
            lobby = new LobbyClient(configuration, network, state -> { history.add(state); events.offer(state); });
        }
        long starts() { return history.stream().filter(LobbyClient.Snapshot::isMatchStarted).count(); }
        LobbyClient.Snapshot await(LobbyClient.Phase phase) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (true) {
                long remaining = deadline - System.nanoTime();
                assertTrue("Timeout waiting for " + phase + ": " + lobby.getSnapshot(), remaining > 0);
                var state = events.poll(remaining, TimeUnit.NANOSECONDS);
                assertNotNull("Missing " + phase + ": " + lobby.getSnapshot(), state);
                if (state.phase() == phase) { return state; }
                assertNotEquals("Unexpected lobby error: " + state, LobbyClient.Phase.ERROR, state.phase());
                assertNotEquals("Unexpected disconnect: " + state, LobbyClient.Phase.DISCONNECTED, state.phase());
            }
        }
    }

    private static final class CountingClient extends NetworkClient {
        final List<ProtocolMessage> sent = new CopyOnWriteArrayList<>(), received = new CopyOnWriteArrayList<>();
        final CountDownLatch joinOffered = new CountDownLatch(1), allowJoin, connected = new CountDownLatch(1), pings = new CountDownLatch(12);
        volatile String connectThreadName;
        CountingClient(int port, boolean holdJoin) {
            super("127.0.0.1", port, null);
            allowJoin = new CountDownLatch(holdJoin ? 1 : 0);
            setListener(null);
        }
        @Override public synchronized void connect() throws java.io.IOException {
            connectThreadName = Thread.currentThread().getName();
            super.connect();
        }
        @Override public boolean sendMessage(ProtocolMessage message) {
            if (message.type() == MessageType.JOIN_ROOM) {
                joinOffered.countDown();
                try { if (!allowJoin.await(3, TimeUnit.SECONDS)) { return false; } }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
            boolean queued = super.sendMessage(message);
            if (queued) { sent.add(message); }
            return queued;
        }
        @Override public void setListener(ConnectionListener delegate) {
            super.setListener(new ConnectionListener() {
                @Override public void onConnected() { connected.countDown(); if (delegate != null) { delegate.onConnected(); } }
                @Override public void onPacketReceived(NetworkPacket packet) { if (delegate != null) { delegate.onPacketReceived(packet); } }
                @Override public void onProtocolMessageReceived(ProtocolMessage message) {
                    received.add(message);
                    if (message.type() == MessageType.PING) { pings.countDown(); }
                    if (delegate != null) { delegate.onProtocolMessageReceived(message); }
                }
                @Override public void onDisconnected(String reason) { if (delegate != null) { delegate.onDisconnected(reason); } }
            });
        }
        long countSent(MessageType type) { return sent.stream().filter(m -> m.type() == type).count(); }
        long countReceived(MessageType type) { return received.stream().filter(m -> m.type() == type).count(); }
        ProtocolMessage firstReceived(MessageType type) { return received.stream().filter(m -> m.type() == type).findFirst().orElseThrow(); }
    }
}
