package com.smashprofs.server.game;

import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Real GameServer and production NetworkClient sockets on localhost/port 0. The existing
 * package-private scheduler injection keeps fixture mutations on the simulation owner thread.
 * Countdown/ticks are advanced deterministically; heartbeat uses a real clock and executor.
 */
public class ClientMatchTcpIntegrationTest {
    private final StepExecutor simulation = new StepExecutor();
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final List<Probe> clients = new ArrayList<>();
    private GameServer server;

    @Before public void start() {
        server = new GameServer(() -> new FixedTickScheduler(simulation, Logger.getLogger("client-match-test")),
                clock::get, new HeartbeatConfiguration(Duration.ofMillis(100), Duration.ofMillis(700)),
                System::nanoTime, () -> Executors.newSingleThreadScheduledExecutor());
        server.start();
    }
    @After public void cleanup() throws Exception {
        try { for (var client : clients) { client.lobby.closeAndWait(5000); } }
        finally { server.stop(); }
    }

    private Probe joined(boolean create) throws Exception {
        var p = new Probe(server.getBoundPort(), create); clients.add(p);
        assertFalse(p.lobby.recordFrameInput(1, true, true, true, true, true));
        assertTrue(p.lobby.startAsync()); p.awaitPhase(LobbyClient.Phase.JOINED);
        assertNull(p.lobby.getMatchSession());
        assertFalse(p.lobby.recordFrameInput(1, true, true, true, true, true));
        assertEquals(0, p.network.inputs().size());
        assertEquals(create ? 1 : 0, p.network.countSent(MessageType.CREATE_ROOM));
        assertEquals(1, p.network.countSent(MessageType.JOIN_ROOM));
        return p;
    }
    private List<Probe> match(int count) throws Exception {
        List<Probe> roster = new ArrayList<>();
        for (int i = 0; i < count; i++) { roster.add(joined(i == 0)); }
        String[] characters = {"Viktor", "Maurice", "Leo", "Alex"};
        for (int i = 0; i < count; i++) {
            assertTrue(roster.get(i).lobby.ready(characters[i]));
            roster.get(i).awaitPhase(LobbyClient.Phase.WAITING_FOR_START);
            assertFalse(roster.get(i).lobby.recordFrameInput(1, true, true, true, true, true));
        }
        simulation.runCycle(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); simulation.runCycle();
        for (var client : roster) {
            client.awaitPhase(LobbyClient.Phase.STARTED);
            assertNotNull(client.lobby.getMatchSession());
            assertEquals(count, client.lobby.getMatchSession().getRoster().players().size());
            assertEquals(client.network.getPlayerId(), client.lobby.getMatchSession().getLocalPlayerId());
        }
        assertEquals(TimeUnit.SECONDS.toNanos(1) / 60, simulation.periodNanos);
        return roster;
    }
    private void cycles(int count) { for (int i = 0; i < count; i++) { simulation.runCycle(); } }
    private WireGameSnapshot state(Probe client, long expectedTick) throws Exception {
        var message = client.network.states.poll(5, TimeUnit.SECONDS);
        assertNotNull("No GAME_STATE received; lobby=" + client.lobby.getSnapshot(), message);
        var snapshot = client.lobby.getMatchSession().getSnapshotBuffer().getLatestSnapshot();
        assertNotNull(snapshot);
        assertEquals(WireGameSnapshot.parse(message.payload()), snapshot);
        assertEquals(expectedTick, snapshot.serverTick());
        snapshot.validateRoster(client.lobby.getSnapshot().match());
        return snapshot;
    }
    private void barrier(Probe client) throws Exception {
        String id = UUID.randomUUID().toString();
        assertTrue(client.network.sendMessage(new ProtocolMessage(MessageType.CONNECT, id, ProtocolV1.object())));
        client.network.await(MessageType.CONNECT_ACK, m -> id.equals(m.requestId()));
    }

    @Test(timeout=20000) public void twoClientsRouteDistinctInputsToTrustedIdentityAndReceiveRealMovement() throws Exception {
        var pair = match(2); var a = pair.get(0); var b = pair.get(1);
        String aid = a.network.getPlayerId().value(), bid = b.network.getPlayerId().value();
        assertNotEquals(aid, bid);
        cycles(2); var before = state(a, 3); assertEquals(before, state(b, 3));
        assertTrue(a.lobby.recordFrameInput(1, false, false, false, false, false)); barrier(a);
        cycles(3); var moved = state(a, 6); assertEquals(moved, state(b, 6));
        assertTrue(moved.player(aid).x() > before.player(aid).x());
        assertEquals("A input must not move B", before.player(bid).x(), moved.player(bid).x(), 0.00001);
        assertTrue(b.lobby.recordFrameInput(-1, false, false, false, false, false)); barrier(b);
        cycles(3); var both = state(a, 9); assertEquals(both, state(b, 9));
        assertTrue(both.player(aid).x() > moved.player(aid).x());
        assertTrue(both.player(bid).x() < moved.player(bid).x());

        var input = a.network.inputs().get(0);
        assertEquals(1, input.version()); assertNull(input.requestId()); assertEquals(8, input.payload().size());
        assertFalse(input.payload().has("playerId")); assertFalse(input.payload().has("sessionId"));
        assertEquals(a.network.getPlayerId(), ProtocolV1.playerInput(input, a.network.getPlayerId()).playerId());
        assertEquals(b.network.getPlayerId(), ProtocolV1.playerInput(b.network.inputs().get(0), b.network.getPlayerId()).playerId());
        assertTrue(input.payload().get("clientTick").isIntegralNumber());
        assertTrue(input.payload().get("clientTick").longValue() >= 0);
        assertTrue(a.network.sendMessage(new ProtocolMessage(MessageType.PLAYER_INPUT, "duplicate", input.payload())));
        assertEquals("DUPLICATE_INPUT", a.network.await(MessageType.ERROR, m -> "duplicate".equals(m.requestId())).payload().get("code").textValue());
        var forged = input.payload().put("sequence", 999).put("playerId", bid).put("moveAxis", -1);
        assertTrue(a.network.sendMessage(new ProtocolMessage(MessageType.PLAYER_INPUT, "forged", forged)));
        assertEquals("UNAUTHORIZED_PLAYER", a.network.await(MessageType.ERROR, m -> "forged".equals(m.requestId())).payload().get("code").textValue());
        assertTrue(a.lobby.recordFrameInput(1, false, false, false, true, false)); barrier(a);
        assertEquals(2, a.network.inputs().get(a.network.inputs().size() - 1).payload().get("sequence").longValue());
        cycles(3); var afterError = state(a, 12); assertEquals(afterError, state(b, 12));
        assertTrue(afterError.player(aid).x() > both.player(aid).x()); assertTrue(afterError.player(aid).blocking());
        assertEquals(List.of(3L, 6L, 9L, 12L), a.lobby.getMatchSession().getSnapshotBuffer().getSnapshots().stream().map(WireGameSnapshot::serverTick).toList());
        assertEquals(0, a.lobby.getMatchSession().getMetrics().getDroppedSnapshots());
        assertEquals(0, b.lobby.getMatchSession().getMetrics().getDroppedSnapshots());
    }

    @Test(timeout=20000) public void fourPlayerSnapshotPreservesFractionalHPAndRealProjectileViews() throws Exception {
        var roster = match(4); cycles(2);
        var before = state(roster.get(0), 3);
        for (int i = 1; i < 4; i++) { assertEquals(before, state(roster.get(i), 3)); }
        var a = roster.get(0); var runtime = server.getRoomManager().getRoom("arena").getMatchRuntime();
        runtime.getMatchState().getPlayer(a.network.getPlayerId()).applyDamage(7.25f); // fixture, simulation owner
        assertTrue(a.lobby.recordFrameInput(0, false, false, false, false, true)); barrier(a);
        cycles(3); var after = state(a, 6);
        assertEquals(92.75f, after.player(a.network.getPlayerId().value()).hp(), 0);
        assertEquals(4, after.players().size()); assertEquals(runtime.getMatchId(), after.matchId());
        assertFalse(after.projectiles().isEmpty());
        var views = runtime.getLatestSnapshot().projectiles(); assertEquals(views.size(), after.projectiles().size());
        for (int i = 0; i < views.size(); i++) {
            var view = views.get(i); var parsed = after.projectiles().get(i);
            assertEquals(view.projectileId(), parsed.projectileId()); assertEquals(view.matchId(), parsed.matchId());
            assertEquals(view.ownerPlayerId().value(), parsed.ownerPlayerId()); assertEquals(view.projectileType(), parsed.projectileType());
            assertEquals(view.spawnTick(), parsed.spawnTick()); assertEquals(view.x(), parsed.x(), 0);
            assertEquals(view.y(), parsed.y(), 0); assertEquals(view.velocityX(), parsed.velocityX(), 0); assertEquals(view.velocityY(), parsed.velocityY(), 0);
        }
        for (int i = 0; i < 4; i++) {
            var client = roster.get(i);
            if (i > 0) { assertEquals(after, state(client, 6)); }
            var local = after.player(client.network.getPlayerId().value());
            assertEquals(i, local.slotIndex());
            assertEquals(client.lobby.getSnapshot().match().localPlayer(client.network.getPlayerId()).characterId(), local.characterId());
        }
    }

    @Test(timeout=20000) public void threePlayerSnapshotsIncludeThirdSlotOnEveryConnection() throws Exception {
        var roster = match(3); cycles(2);
        WireGameSnapshot common = null;
        for (var p : roster) {
            var received = state(p, 3);
            assertEquals(3, received.players().size());
            assertEquals(2, received.players().get(2).slotIndex());
            if (common == null) { common = received; } else { assertEquals(common, received); }
        }
    }

    @Test(timeout=20000) public void transportDisconnectCallbackClearsRejectedInputSnapshotsAndIdentity() throws Exception {
        var pair = match(2); var a = pair.get(0); cycles(2); state(a, 3); state(pair.get(1), 3);
        var session = a.lobby.getMatchSession();
        a.network.rejectInputs = true;
        assertFalse(a.lobby.recordFrameInput(1, true, true, true, true, true));
        assertTrue(session.getPendingInputCount() > 0);
        a.network.disconnectAndWait(5000);
        a.awaitPhase(LobbyClient.Phase.DISCONNECTED);
        assertNull(a.lobby.getMatchSession()); assertNull(a.lobby.getSnapshot().localPlayerId());
        assertNull(a.lobby.getSnapshot().match()); assertTrue(a.lobby.getSnapshot().members().isEmpty());
        assertFalse(session.isActive()); assertEquals(0, session.getPendingInputCount()); assertTrue(session.getSnapshotBuffer().isEmpty());
        assertFalse(a.lobby.recordFrameInput(0, false, false, false, false, false));
        assertEquals(0, a.network.inputs().size());
        cycles(3); state(pair.get(1), 6);
    }

    @Test(timeout=20000) public void enqueueRejectionRetriesEdgesAndDisconnectClearsMatchWithoutDuplicateLoop() throws Exception {
        var pair = match(2); var a = pair.get(0); var session = a.lobby.getMatchSession();
        var start = a.network.await(MessageType.GAME_START, m -> true);
        a.lobby.onProtocolMessageReceived(start); assertSame(session, a.lobby.getMatchSession());
        assertEquals(0, a.network.inputs().size()); // GAME_START does not create a background input loop
        a.network.rejectInputs = true;
        assertFalse(a.lobby.recordFrameInput(1, true, false, false, true, false));
        assertFalse(a.lobby.recordFrameInput(-1, false, true, true, false, true));
        assertEquals(0, a.network.inputs().size()); assertTrue(session.getPendingInputCount() > 0);
        a.network.rejectInputs = false; assertTrue(session.flushInput()); barrier(a);
        var sent = a.network.inputs().get(0).payload();
        assertEquals(1, sent.get("sequence").longValue()); assertEquals(-1, sent.get("moveAxis").floatValue(), 0);
        assertTrue(sent.get("jumpPressed").booleanValue()); assertTrue(sent.get("stompPressed").booleanValue());
        assertTrue(sent.get("meleePressed").booleanValue()); assertTrue(sent.get("abilityPressed").booleanValue()); assertFalse(sent.get("blockHeld").booleanValue());
        assertTrue(a.lobby.recordFrameInput(0, false, false, false, true, false)); barrier(a);
        var released = a.network.inputs().get(1).payload();
        assertEquals(2, released.get("sequence").longValue()); assertTrue(released.get("blockHeld").booleanValue());
        assertFalse(released.get("jumpPressed").booleanValue()); assertFalse(released.get("stompPressed").booleanValue());
        assertFalse(released.get("meleePressed").booleanValue()); assertFalse(released.get("abilityPressed").booleanValue());
        cycles(2); state(a, 3); state(pair.get(1), 3);
        a.network.rejectInputs = true;
        assertFalse(a.lobby.recordFrameInput(1, true, true, true, true, true));
        a.lobby.closeAndWait(5000);
        assertNull(a.network.getPlayerId()); assertNull(a.lobby.getMatchSession());
        assertNull(a.lobby.getSnapshot().localPlayerId()); assertNull(a.lobby.getSnapshot().match());
        assertTrue(a.lobby.getSnapshot().members().isEmpty());
        assertFalse(session.isActive()); assertTrue(session.getSnapshotBuffer().isEmpty()); assertEquals(0, session.getPendingInputCount());
        a.network.rejectInputs = false;
        assertFalse(session.flushInput()); assertFalse(a.lobby.recordFrameInput(1, true, true, true, true, true));
        assertEquals(2, a.network.inputs().size());
        a.lobby.onProtocolMessageReceived(start); assertNull(a.lobby.getMatchSession());
        cycles(3); state(pair.get(1), 6); // surviving client still receives snapshots
    }

    @Test(timeout=20000) public void idleHeartbeatContinuesDuringMatchWithoutGameplayInput() throws Exception {
        var pair = match(2); long observedStart = System.nanoTime(); cycles(2);
        for (var p : pair) { state(p, 3); }
        for (var p : pair) {
            for (int i = 0; i < 12; i++) { p.network.await(MessageType.PING, m -> true); }
            assertTrue(p.network.isConnected()); assertTrue(p.lobby.getMatchSession().isActive());
            assertEquals(0, p.network.inputs().size());
        }
        assertEquals(2, server.getConnectionManager().size());
        cycles(3); for (var p : pair) { state(p, 6); }
        long elapsedTicks = (System.nanoTime() - observedStart) * 60 / TimeUnit.SECONDS.toNanos(1);
        assertTrue(pair.get(0).lobby.recordFrameInput(0, false, false, false, false, false));
        var input = pair.get(0).network.inputs().get(0).payload();
        assertEquals(1, input.get("sequence").longValue());
        assertTrue("clientTick uses elapsed local 60 Hz match time, not frame count", input.get("clientTick").longValue() >= elapsedTicks);
    }

    @Test(timeout=20000) public void finalSnapshotOutsideCadenceStopsInputWithoutAddingGameEndPresentation() throws Exception {
        var pair = match(2); cycles(2); for (var p : pair) { state(p, 3); }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); cycles(1);
        for (var p : pair) {
            var finalState = state(p, 4); assertEquals("FINISHED", finalState.phase()); assertEquals(0, finalState.remainingTimeMs());
            assertFalse(p.lobby.recordFrameInput(1, true, true, true, true, true));
            assertEquals(0, p.lobby.getMatchSession().getPendingInputCount()); assertEquals(0, p.lobby.getMatchSession().getMetrics().getDroppedSnapshots());
            assertEquals(0, p.network.inputs().size());
        }
    }

    @Test(timeout=20000) public void fullTcpFlowPresentsDrawFromAuthoritativeGameEnd() throws Exception { finishAndPresent(2, true); }
    @Test(timeout=20000) public void fullTcpFlowPresentsWinnerInSlotThreeFromAuthoritativeGameEnd() throws Exception { finishAndPresent(4, false); }
    private void finishAndPresent(int count, boolean draw) throws Exception {
        var roster = match(count); cycles(2);
        List<OnlineMatchPresentation> views = new ArrayList<>();
        for (var p : roster) {
            state(p, 3);
            var view = new OnlineMatchPresentation(p.lobby.getMatchSession()); view.update(); views.add(view);
            assertEquals(count, view.getPlayers().size());
            assertEquals(1, view.getPlayers().stream().filter(OnlineMatchPresentation.PlayerView::local).count());
        }
        var runtime = server.getRoomManager().getRoom("arena").getMatchRuntime();
        for (int i = 0; i < count; i++) {
            if (draw || i != count - 1) { runtime.getMatchState().getPlayer(roster.get(i).network.getPlayerId()).applyDamage(7.25f); }
        }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); cycles(1);
        for (int i = 0; i < count; i++) {
            var p = roster.get(i); var finalState = state(p, 4);
            var end = p.network.await(MessageType.GAME_END, m -> true);
            var result = p.lobby.getMatchSession().getResult(); assertNotNull(result);
            assertEquals(WireMatchResult.parse(end.payload()), result); assertEquals(runtime.getMatchId(), result.matchId());
            assertEquals(draw, result.isDraw()); assertEquals("TIME_LIMIT", result.finishReason());
            assertEquals(finalState.serverTick(), result.finishedAtTick()); assertEquals(count, result.participants().size());
            if (draw) { assertNull(result.winnerId()); }
            else { assertEquals(roster.get(count - 1).network.getPlayerId().value(), result.winnerId()); assertEquals(count - 1, result.winner().slotIndex()); }
            assertEquals(92.75f, result.participants().get(0).finalHp(), 0);
            var view = views.get(i); view.update(); assertEquals(result, view.takeResultTransition());
            p.lobby.onProtocolMessageReceived(end); assertSame(result, p.lobby.getMatchSession().getResult());
            assertNull(view.takeResultTransition()); assertEquals("FINISHED", view.getFrame().authoritative().phase());
            assertFalse(p.lobby.recordFrameInput(1, true, true, true, true, true)); assertEquals(0, p.network.inputs().size());
            view.close(); assertTrue(p.network.isConnected()); // screen/presentation cleanup doesn't close Game-owned TCP
        }
    }

    private static final class Probe {
        final ObservedClient network;
        final LobbyClient lobby;
        final BlockingQueue<LobbyClient.Snapshot> phases = new LinkedBlockingQueue<>();
        Probe(int port, boolean create) {
            network = new ObservedClient(port);
            lobby = new LobbyClient(new LobbyConfiguration("127.0.0.1", port, "arena",
                    create ? LobbyConfiguration.Mode.CREATE : LobbyConfiguration.Mode.JOIN), network, phases::offer);
        }
        void awaitPhase(LobbyClient.Phase expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime(); assertTrue("Timeout: " + lobby.getSnapshot(), remaining > 0);
                var state = phases.poll(remaining, TimeUnit.NANOSECONDS); assertNotNull("Missing phase " + expected, state);
                if (state.phase() == expected) { return; }
                assertNotEquals(state.toString(), LobbyClient.Phase.ERROR, state.phase());
                assertNotEquals(state.toString(), LobbyClient.Phase.DISCONNECTED, state.phase());
            }
        }
    }
    private static final class ObservedClient extends NetworkClient {
        final List<ProtocolMessage> sent = new CopyOnWriteArrayList<>();
        final BlockingQueue<ProtocolMessage> received = new LinkedBlockingQueue<>(), states = new LinkedBlockingQueue<>();
        volatile boolean rejectInputs;
        ObservedClient(int port) { super("127.0.0.1", port, null); }
        @Override public boolean sendMessage(ProtocolMessage message) {
            if (rejectInputs && message.type() == MessageType.PLAYER_INPUT) { return false; }
            boolean accepted = super.sendMessage(message);
            if (accepted) { sent.add(message); }
            return accepted;
        }
        @Override public void setListener(ConnectionListener delegate) {
            super.setListener(new ConnectionListener() {
                @Override public void onConnected() { delegate.onConnected(); }
                @Override public void onPacketReceived(NetworkPacket packet) { delegate.onPacketReceived(packet); }
                @Override public void onDisconnected(String reason) { delegate.onDisconnected(reason); }
                @Override public void onProtocolMessageReceived(ProtocolMessage message) {
                    delegate.onProtocolMessageReceived(message);
                    received.offer(message);
                    if (message.type() == MessageType.GAME_STATE) { states.offer(message); }
                }
            });
        }
        List<ProtocolMessage> inputs() { return sent.stream().filter(m -> m.type() == MessageType.PLAYER_INPUT).toList(); }
        long countSent(MessageType type) { return sent.stream().filter(m -> m.type() == type).count(); }
        ProtocolMessage await(MessageType type, Predicate<ProtocolMessage> filter) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime(); assertTrue("Timeout for " + type, remaining > 0);
                var m = received.poll(remaining, TimeUnit.NANOSECONDS); assertNotNull("No " + type, m);
                if (m.type() == type && filter.test(m)) { return m; }
            }
        }
    }
    private static final class StepExecutor extends ScheduledThreadPoolExecutor {
        Runnable cycle;
        long periodNanos;
        StepExecutor() { super(1); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delay, long period, TimeUnit unit) {
            cycle = task; periodNanos = unit.toNanos(period);
            return super.schedule(() -> { }, 1, TimeUnit.DAYS);
        }
        void runCycle() { cycle.run(); }
    }
}
