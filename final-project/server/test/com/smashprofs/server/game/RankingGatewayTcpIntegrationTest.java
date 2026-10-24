package com.smashprofs.server.game;

import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.RoomPhase;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Recording gateway chỉ trong test; Socket/native runtime/result/evaluator và completion callback thật. */
public class RankingGatewayTcpIntegrationTest {
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final List<TcpTestClient> clients = new ArrayList<>();
    private ManualScheduledExecutor executor;
    private GameServer server;
    private final BlockingQueue<AuthoritativeMatchResult> received = new LinkedBlockingQueue<>();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    @After public void stop() throws Exception {
        release.countDown(); try { if (server != null) { server.stop(); } } finally { for (var client : clients) { client.close(); } }
    }
    private void server(RankingGateway gateway) {
        server = gateway == null ? new GameServer(this::scheduler, clock::get) : GameServer.withRankingGateway(this::scheduler, clock::get, gateway);
        server.start();
    }
    private FixedTickScheduler scheduler() { executor = new ManualScheduledExecutor(); return new FixedTickScheduler(executor, Logger.getLogger("ranking-e2e")); }
    private List<TcpTestClient> roster(String id, int count) throws Exception {
        var roster = new ArrayList<TcpTestClient>();
        for (int i = 0; i < count; i++) {
            var client = new TcpTestClient(server.getBoundPort()); clients.add(client); roster.add(client); client.connect();
            if (i == 0) { assertEquals(MessageType.ROOM_CREATED, client.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", id)).type()); }
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", id)).type());
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.PLAYER_READY, ProtocolV1.object().put("characterId", "Alex").put("ready", true)).type());
        }
        return roster;
    }
    private void launch() throws Exception {
        executor.runCycle(); assertEquals(0, server.getResultPublisher().getTrackedCount());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
        for (var client : clients) { client.awaitType(MessageType.GAME_START); }
        assertEquals(0, server.getResultPublisher().getTrackedCount());
    }
    private MatchRuntime runtime(String id) { return server.getRoomManager().getRoom(id).getMatchRuntime(); }
    private void status(String id, MatchResultPublisher.Status expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.getResultPublisher().getStatus(id) != expected && System.nanoTime() < deadline) { Thread.yield(); }
        assertEquals(expected, server.getResultPublisher().getStatus(id));
    }
    private void end(String id, List<TcpTestClient> roster) throws Exception {
        var result = runtime(id).getMatchResult(); assertNotNull(result); assertEquals(RoomPhase.FINISHED, server.getRoomManager().getRoom(id).getPhase());
        for (var client : roster) {
            var state = client.awaitType(MessageType.GAME_STATE); var end = client.awaitType(MessageType.GAME_END);
            assertEquals("FINISHED", state.payload().get("phase").textValue()); assertEquals(result.finishedAtTick(), state.payload().get("serverTick").longValue());
            assertEquals(result.matchId(), end.payload().get("matchId").textValue()); assertEquals(id, end.payload().get("roomId").textValue());
            assertEquals(result.finishReason().name(), end.payload().get("finishReason").textValue());
            assertEquals(result.participants().size(), end.payload().get("participants").size());
            if (result.isDraw()) { assertTrue(end.payload().get("winnerId").isNull()); }
            else { assertEquals(result.winnerId().value(), end.payload().get("winnerId").textValue()); }
            for (var p : result.participants()) {
                var wire = end.payload().get("participants").get(p.slotIndex());
                assertEquals(p.playerId().value(), wire.get("playerId").textValue()); assertEquals(p.finalHp(), wire.get("finalHp").doubleValue(), 0.00001);
                assertEquals(p.characterId(), wire.get("characterId").textValue()); assertEquals(p.slotIndex(), wire.get("slotIndex").intValue());
                assertEquals(p.eliminated(), wire.get("eliminated").booleanValue());
            }
            var history = client.received(); assertEquals(state, history.get(history.indexOf(end) - 1));
            assertEquals(1, history.stream().filter(m -> m.type() == MessageType.GAME_END).count());
        }
    }
    @Test(timeout=20000) public void concurrentTwoThreeFourPlayerMatchesPublishExactResultsAndIndependentGameEnds() throws Exception {
        server(received::add); var a = roster("a2", 2); var b = roster("b3", 3); var c = roster("c4", 4); launch();
        // Fixture HP chỉ tạo điều kiện winner; evaluator production vẫn tạo result tại tick hoàn tất.
        runtime("a2").getMatchState().getPlayer(a.get(1).playerId()).applyDamage(0.25f);
        for (int i = 1; i < 4; i++) { runtime("c4").getMatchState().getPlayer(c.get(i).playerId()).applyDamage(100); }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle();
        var expected = Map.of(runtime("a2").getMatchId(), runtime("a2").getMatchResult(),
                runtime("b3").getMatchId(), runtime("b3").getMatchResult(), runtime("c4").getMatchId(), runtime("c4").getMatchResult());
        var observedIds = new HashSet<String>();
        for (int i = 0; i < 3; i++) {
            var result = received.poll(5, TimeUnit.SECONDS); assertNotNull(result); assertSame(expected.get(result.matchId()), result);
            assertTrue(observedIds.add(result.matchId()));
            status(result.matchId(), MatchResultPublisher.Status.SUBMITTED);
        }
        assertEquals(expected.keySet(), observedIds);
        assertEquals(a.get(0).playerId(), runtime("a2").getMatchResult().winnerId()); assertTrue(runtime("b3").getMatchResult().isDraw());
        assertEquals(c.get(0).playerId(), runtime("c4").getMatchResult().winnerId());
        end("a2", a); end("b3", b); end("c4", c);
        for (int i = 0; i < 10; i++) { executor.runCycle(); }
        assertEquals(3, server.getResultPublisher().getTrackedCount()); assertTrue(received.isEmpty());
        for (var client : clients) { client.barrier(); client.assertQuiet(); }
    }
    @Test(timeout=20000) public void slowFailingGatewayCannotBlockSimulationGameEndOrNativeRoomCleanup() throws Exception {
        server(result -> { received.add(result); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); throw new IllegalStateException("ranking down"); });
        var a = roster("a4", 4); var b = roster("b2", 2); launch(); var ma = runtime("a4"); var mb = runtime("b2");
        for (int i = 1; i < 4; i++) { ma.getMatchState().getPlayer(a.get(i).playerId()).applyDamage(100); }
        executor.runCycle(); assertTrue(entered.await(5, TimeUnit.SECONDS)); var result = ma.getMatchResult(); assertSame(result, received.poll(5, TimeUnit.SECONDS));
        end("a4", a); assertEquals(MatchResultPublisher.Status.IN_FLIGHT, server.getResultPublisher().getStatus(result.matchId()));
        server.getRoomManager().removeRoom("a4"); assertTrue(ma.isClosed()); assertTrue(ma.isPhysicsDisposed());
        assertFalse(mb.isPhysicsDisposed()); assertNull(mb.getMatchResult()); executor.runCycle();
        for (var client : b) { var state = client.awaitType(MessageType.GAME_STATE); assertEquals("b2", state.payload().get("roomId").textValue()); assertEquals(3, state.payload().get("serverTick").longValue()); }
        release.countDown(); status(result.matchId(), MatchResultPublisher.Status.FAILED); assertSame(result, ma.getMatchResult()); assertTrue(received.isEmpty());
        for (var client : a) { client.barrier(); client.assertQuiet(); }
        assertTrue(server.isRunning()); assertEquals(RoomPhase.PLAYING, server.getRoomManager().getRoom("b2").getPhase());
    }
    @Test(timeout=15000) public void noResultDuringLobbyCountdownPlayingDisconnectOrUnfinishedShutdown() throws Exception {
        server(received::add); var roster = roster("room", 2);
        assertEquals(0, server.getResultPublisher().getTrackedCount()); launch(); var match = runtime("room");
        roster.get(0).close(); executor.runCycle(); assertNull(match.getMatchResult());
        assertEquals(0, server.getResultPublisher().getTrackedCount()); server.stop();
        assertNull(match.getMatchResult()); assertTrue(received.isEmpty()); assertTrue(match.isPhysicsDisposed());
    }
    @Test(timeout=15000) public void missingMemberThreeServiceIsVisibleWithoutBreakingGameEnd() throws Exception {
        server(null); var roster = roster("room", 2); launch(); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle();
        end("room", roster); assertEquals(MatchResultPublisher.Status.NOT_CONFIGURED,
                server.getResultPublisher().getStatus(runtime("room").getMatchId())); assertTrue(server.isRunning()); assertTrue(received.isEmpty());
    }
    @Test(timeout=20000) public void restartUsesFreshPublisherAndNoStaleMatchIds() throws Exception {
        server(received::add); var roster = roster("room", 2); launch(); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle(); end("room", roster);
        var first = received.poll(5, TimeUnit.SECONDS); assertNotNull(first); var oldPublisher = server.getResultPublisher();
        server.stop(); assertTrue(oldPublisher.isTerminated()); clients.forEach(c -> { try { c.close(); } catch (Exception e) { throw new RuntimeException(e); } }); clients.clear();
        server.start(); assertNotSame(oldPublisher, server.getResultPublisher()); assertEquals(0, server.getResultPublisher().getTrackedCount());
        var next = roster("room", 2); launch(); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle(); end("room", next);
        var second = received.poll(5, TimeUnit.SECONDS); assertNotNull(second); assertNotEquals(first.matchId(), second.matchId()); assertEquals(1, server.getResultPublisher().getTrackedCount());
    }
    @Test(timeout=20000) public void serverShutdownDisposesWorldsAndReportsUnconfirmedAndCancelledPublications() throws Exception {
        server(result -> { entered.countDown(); new CountDownLatch(1).await(); });
        var a = roster("a2", 2); var b = roster("b4", 4); launch();
        var ma = runtime("a2"); var mb = runtime("b4"); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); executor.runCycle();
        assertTrue(entered.await(5, TimeUnit.SECONDS)); end("a2", a); end("b4", b);
        var publisher = server.getResultPublisher(); assertEquals(1, publisher.getPendingCount()); server.stop();
        assertTrue(ma.isPhysicsDisposed()); assertTrue(mb.isPhysicsDisposed()); assertEquals(0, publisher.getPendingCount());
        assertEquals(Set.of(MatchResultPublisher.Status.SHUTDOWN_UNCONFIRMED, MatchResultPublisher.Status.CANCELLED_SHUTDOWN),
                Set.of(publisher.getStatus(ma.getMatchId()), publisher.getStatus(mb.getMatchId())));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!publisher.isTerminated() && System.nanoTime() < deadline) { Thread.yield(); }
        assertTrue(publisher.isTerminated()); for (var client : clients) { client.assertEof(); }
    }
}
