package com.smashprofs.server.game;

import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.RoomPhase;
import org.junit.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Socket/codec thật và clock/executor manual đã có; không bỏ countdown hoặc thêm lifecycle. */
public class GameStartTcpTest {
    private final ManualScheduledExecutor executor = new ManualScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final AtomicInteger clockReads = new AtomicInteger(), failAt = new AtomicInteger(-1);
    private final List<TcpTestClient> clients = new ArrayList<>();
    private GameServer server;
    @Before public void start() {
        server = new GameServer(() -> new FixedTickScheduler(executor, Logger.getLogger("game-start-test")), () -> {
            if (clockReads.incrementAndGet() == failAt.get()) { throw new IllegalStateException("Lỗi clock fixture tại runtime.start"); }
            return clock.get();
        }); server.start();
    }
    @After public void stop() throws Exception { try { server.stop(); } finally { for (var c : clients) { c.close(); } } }
    private TcpTestClient client() throws Exception { var c = new TcpTestClient(server.getBoundPort()); clients.add(c); c.connect(); return c; }
    private List<TcpTestClient> roster(String id, int count) throws Exception {
        var result = new ArrayList<TcpTestClient>(); String[] characters = {"Alex", "Leo", "Viktor", "Maurice"};
        for (int i = 0; i < count; i++) {
            var c = client(); if (i == 0) { assertEquals(MessageType.ROOM_CREATED, c.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", id)).type()); }
            assertEquals(MessageType.ROOM_STATE, c.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", id)).type());
            assertEquals(MessageType.ROOM_STATE, c.request(MessageType.PLAYER_READY, ProtocolV1.object().put("characterId", characters[i]).put("ready", true)).type()); result.add(c);
        }
        return result;
    }
    private void launch() { executor.runCycle(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle(); }
    private void cycles(int count) { for (int i = 0; i < count; i++) { executor.runCycle(); } }
    private ProtocolMessage checkStart(TcpTestClient client, String id) throws Exception {
        var start = client.awaitType(MessageType.GAME_START); var runtime = server.getRoomManager().getRoom(id).getMatchRuntime();
        assertTrue(runtime.isStarted()); assertEquals(1, start.version()); assertNull(start.requestId());
        var p = start.payload(); assertEquals(3, p.size()); assertEquals(id, p.get("roomId").textValue()); assertEquals(runtime.getMatchId(), p.get("matchId").textValue());
        assertEquals(runtime.getParticipants().size(), p.get("players").size());
        for (var participant : runtime.getParticipants()) {
            com.fasterxml.jackson.databind.JsonNode wire = null;
            // Slot không compact sau leave: mảng sort theo slot nhưng index mảng không phải slotIndex.
            for (var candidate : p.get("players")) {
                if (candidate.get("slotIndex").intValue() == participant.slotIndex()) { wire = candidate; break; }
            }
            assertNotNull(wire); assertEquals(4, wire.size());
            assertEquals(participant.playerId().value(), wire.get("playerId").textValue()); assertEquals(participant.slotIndex(), wire.get("slotIndex").intValue());
            assertEquals(participant.characterId(), wire.get("characterId").textValue());
            assertEquals(participant.displayName(), wire.get("displayName").textValue());
        }
        assertFalse(client.received().stream().anyMatch(m -> m.type() == MessageType.GAME_STATE || m.type() == MessageType.GAME_END));
        return start;
    }
    private void everyone(int count) throws Exception {
        var roster = roster("r", count); launch(); var expected = checkStart(roster.get(0), "r");
        for (int i = 1; i < count; i++) { assertEquals(expected, checkStart(roster.get(i), "r")); }
        cycles(2);
        for (var c : roster) {
            var state = c.awaitType(MessageType.GAME_STATE); assertEquals(expected.payload().get("matchId"), state.payload().get("matchId"));
            assertEquals(3, state.payload().get("serverTick").longValue()); c.barrier();
            assertEquals(1, c.received().stream().filter(m -> m.type() == MessageType.GAME_START).count());
            c.assertQuiet();
        }
    }
    @Test public void twoClientsReceiveGameStartBeforeFirstState() throws Exception { everyone(2); }
    @Test public void threeClientsReceiveGameStartBeforeFirstState() throws Exception { everyone(3); }
    @Test public void fourClientsReceiveGameStartBeforeFirstState() throws Exception { everyone(4); }
    @Test public void notSentUntilFullCountdownAndUnjoinedIsExcluded() throws Exception {
        var outsider = client(); var a = roster("r", 2); for (var c : a) { c.assertQuiet(); }
        executor.runCycle(); clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2999)); executor.runCycle();
        assertEquals(RoomPhase.COUNTDOWN, server.getRoomManager().getRoom("r").getPhase()); for (var c : a) { c.assertQuiet(); }
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1)); executor.runCycle(); for (var c : a) { checkStart(c, "r"); } outsider.assertQuiet();
    }
    @Test public void simultaneousRoomsAndCancelledCountdownAreIsolated() throws Exception {
        var a = roster("a", 2); var b = roster("b", 3); var canceled = roster("cancel", 2); executor.runCycle();
        assertEquals(MessageType.ROOM_STATE, canceled.get(0).request(MessageType.PLAYER_READY, ProtocolV1.object().put("ready", false)).type());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
        for (var c : a) { checkStart(c, "a"); } for (var c : b) { checkStart(c, "b"); }
        for (var c : canceled) { c.assertQuiet(); } assertNull(server.getRoomManager().getRoom("cancel").getMatchRuntime());
        assertNotEquals(server.getRoomManager().getRoom("a").getMatchRuntime().getMatchId(), server.getRoomManager().getRoom("b").getMatchRuntime().getMatchId());
    }
    @Test public void repeatedReadyPhaseReadsAndCallbacksDoNotDuplicateStart() throws Exception {
        var a = roster("r", 2);
        for (int i = 0; i < 3; i++) { a.get(0).request(MessageType.PLAYER_READY, ProtocolV1.object().put("ready", true)); }
        launch(); for (var c : a) { checkStart(c, "r"); } var room = server.getRoomManager().getRoom("r"); var runtime = room.getMatchRuntime();
        for (int i = 0; i < 20; i++) { room.getPhase(); runtime.getMatchSetup(); }
        var rejected = a.get(0).request(MessageType.PLAYER_READY, ProtocolV1.object().put("ready", true)); assertEquals(MessageType.ERROR, rejected.type());
        cycles(2); for (var c : a) { c.awaitType(MessageType.GAME_STATE); c.barrier(); c.assertQuiet(); assertEquals(1, c.received().stream().filter(m -> m.type() == MessageType.GAME_START).count()); }
        assertSame(runtime, room.getMatchRuntime());
    }
    @Test public void runtimeStartFailureDoesNotSendFalseStart() throws Exception {
        var a = roster("r", 2); executor.runCycle();
        // updateCountdown đọc clock lần kế; runtime.start đọc lần tiếp trong initialization rồi throw.
        failAt.set(clockReads.get() + 2); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
        var room = server.getRoomManager().getRoom("r"); assertNotNull(room.getLifecycleFailure()); assertNull(room.getMatchRuntime());
        for (var c : a) { c.assertQuiet(); } cycles(3); for (var c : a) { c.assertQuiet(); } assertTrue(server.isRunning());
    }
    @Test public void disconnectedSessionDoesNotBlockOtherStartRecipients() throws Exception {
        var a = roster("r", 3); a.get(0).close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.getRoomManager().getRoom("r").getPlayerCount() != 2 && System.nanoTime() < deadline) { Thread.yield(); }
        assertEquals(2, server.getRoomManager().getRoom("r").getPlayerCount());
        launch(); checkStart(a.get(1), "r"); checkStart(a.get(2), "r");
    }
    @Test public void pendingStartStillPrecedesTerminalStateAndEnd() throws Exception {
        var a = roster("r", 2); launch(); // Không đọc start trước khi finish: worker phải giữ ordering.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); cycles(1);
        for (var c : a) {
            var start = c.awaitType(MessageType.GAME_START); var state = c.awaitType(MessageType.GAME_STATE); var end = c.awaitType(MessageType.GAME_END);
            assertEquals("FINISHED", state.payload().get("phase").textValue()); assertEquals(start.payload().get("matchId"), end.payload().get("matchId"));
            var h = c.received(); assertEquals(List.of(MessageType.GAME_START, MessageType.GAME_STATE, MessageType.GAME_END),
                    h.stream().filter(m -> m.requestId() == null).map(ProtocolMessage::type).toList()); c.assertQuiet();
        }
    }
}
