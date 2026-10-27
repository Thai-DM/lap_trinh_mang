package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.utils.Array;
import com.fasterxml.jackson.databind.JsonNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomPhase;
import org.junit.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** TCP/codec/World thật; tái sử dụng clock, executor và client test của các bước trước. */
public class FourClientTcpMatchEndToEndTest {
    private static final double EPS = 0.00001;
    private static final String[] CHARACTERS = {"Alex", "Leo", "Luca", "Viktor"};
    private final ManualScheduledExecutor executor = new ManualScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final List<TcpTestClient> clients = new ArrayList<>();
    private GameServer server;

    @Before public void start() {
        server = new GameServer(() -> new FixedTickScheduler(executor, Logger.getLogger("four-client-e2e")), clock::get);
        server.start(); assertTrue(server.isRunning()); assertTrue(server.getBoundPort() > 0);
    }
    @After public void stop() throws Exception {
        try { server.stop(); } finally { for (var client : clients) { client.close(); } }
    }
    private TcpTestClient client() throws Exception {
        var client = new TcpTestClient(server.getBoundPort()); clients.add(client); client.connect(); return client;
    }
    private List<TcpTestClient> roster(String id, int count) throws Exception {
        var roster = new ArrayList<TcpTestClient>();
        for (int i = 0; i < count; i++) {
            var client = client(); roster.add(client);
            if (i == 0) {
                var created = client.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", id));
                assertEquals(MessageType.ROOM_CREATED, created.type()); assertEquals(id, created.payload().get("roomId").textValue());
                assertEquals(0, room(id).getPlayerCount());
            }
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", id)).type());
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.PLAYER_READY,
                    ProtocolV1.object().put("characterId", CHARACTERS[i]).put("ready", true)).type());
        }
        assertEquals(count, roster.stream().map(TcpTestClient::playerId).distinct().count());
        assertEquals(count, room(id).getPlayerCount());
        for (int i = 0; i < count; i++) {
            var client = roster.get(i); UUID.fromString(client.playerId().value());
            var session = server.getConnectionManager().getSessions().stream()
                    .filter(s -> client.playerId().equals(s.getPlayerId())).findFirst().orElseThrow();
            assertEquals(id, session.getRoomId()); assertTrue(session.isConnected());
            assertEquals(i, room(id).getPlayerSlot(client.playerId()).slotIndex());
            assertEquals(CHARACTERS[i], room(id).getSelectedCharacter(client.playerId()));
        }
        return List.copyOf(roster);
    }
    private GameRoom room(String id) { return server.getRoomManager().getRoom(id); }
    private MatchRuntime runtime(String id) { return room(id).getMatchRuntime(); }
    private void launch(Map<String, List<TcpTestClient>> rosters) throws Exception {
        executor.runCycle();
        for (String id : rosters.keySet()) { assertEquals(RoomPhase.COUNTDOWN, room(id).getPhase()); assertNull(runtime(id)); }
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2999)); executor.runCycle();
        for (var roster : rosters.values()) { for (var client : roster) { client.assertQuiet(); } }
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1)); executor.runCycle();
        assertEquals(1, executor.getScheduleCount());
        for (var entry : rosters.entrySet()) {
            String id = entry.getKey(); var match = runtime(id); assertEquals(RoomPhase.PLAYING, room(id).getPhase());
            assertEquals(1, match.getMatchState().getServerTick()); ProtocolMessage expected = null;
            for (var client : entry.getValue()) {
                var start = client.awaitType(MessageType.GAME_START);
                if (expected == null) { expected = start; } else { assertEquals(expected, start); }
                assertEquals(match.getMatchId(), start.payload().get("matchId").textValue());
                assertEquals(id, start.payload().get("roomId").textValue()); assertNull(start.requestId());
                assertEquals(entry.getValue().size(), start.payload().get("players").size());
                for (var participant : match.getParticipants()) {
                    var wire = start.payload().get("players").get(participant.slotIndex());
                    assertEquals(participant.playerId().value(), wire.get("playerId").textValue());
                    assertEquals(participant.slotIndex(), wire.get("slotIndex").intValue());
                    assertEquals(participant.characterId(), wire.get("characterId").textValue());
                }
                assertFalse(client.received().stream().anyMatch(m -> m.type() == MessageType.GAME_STATE || m.type() == MessageType.GAME_END));
            }
        }
    }
    private void cycles(int count) {
        for (int i = 0; i < count; i++) {
            var ticks = new HashMap<MatchRuntime, Long>();
            for (var room : server.getRoomManager().getRooms()) {
                var match = room.getMatchRuntime();
                if (match != null && !match.isFinished()) { ticks.put(match, match.getMatchState().getServerTick()); }
            }
            executor.runCycle(); ticks.forEach((match, tick) -> assertEquals(tick + 1, match.getMatchState().getServerTick()));
        }
    }
    /** Chỉ quan sát các tài nguyên đã có; không submit input, step hoặc mutate Body từ helper. */
    private Object field(MatchRuntime match, String name) throws Exception {
        var field = MatchRuntime.class.getDeclaredField(name); field.setAccessible(true); return field.get(match);
    }
    private int pending(MatchRuntime match) throws Exception { return ((InputQueue) field(match, "inputQueue")).getPendingCount(); }
    private HeadlessPhysicsWorld physics(MatchRuntime match) throws Exception { return (HeadlessPhysicsWorld) field(match, "physicsWorld"); }
    private Map<PlayerId, List<Float>> motion(MatchRuntime match) throws Exception {
        var bodies = new Array<Body>(); physics(match).getWorld().getBodies(bodies);
        var result = new HashMap<PlayerId, List<Float>>();
        for (var body : bodies) {
            if (body.getUserData() instanceof PlayerId id) {
                result.put(id, List.of(body.getPosition().x, body.getPosition().y, body.getLinearVelocity().x, body.getLinearVelocity().y));
            }
        }
        return Map.copyOf(result);
    }
    private JsonNode player(ProtocolMessage state, PlayerId id) {
        for (var player : state.payload().get("players")) { if (id.value().equals(player.get("playerId").textValue())) { return player; } }
        throw new AssertionError("Thiếu player trong snapshot: " + id);
    }
    private ProtocolMessage state(String id, List<TcpTestClient> roster) throws Exception {
        var match = runtime(id); var snapshot = match.getLatestSnapshot(); ProtocolMessage expected = null;
        for (var client : roster) {
            var message = client.awaitType(MessageType.GAME_STATE);
            if (expected == null) { expected = message; } else { assertEquals(expected, message); }
            assertEquals(1, message.version()); assertNull(message.requestId()); var payload = message.payload();
            assertEquals(id, payload.get("roomId").textValue()); assertEquals(match.getMatchId(), payload.get("matchId").textValue());
            assertEquals(snapshot.serverTick(), payload.get("serverTick").longValue()); assertEquals(match.getParticipants().size(), payload.get("players").size());
            for (var p : snapshot.players()) {
                var wire = payload.get("players").get(p.slotIndex());
                assertEquals(p.playerId().value(), wire.get("playerId").textValue());
                assertEquals(p.slotIndex(), wire.get("slotIndex").intValue()); assertEquals(p.characterId(), wire.get("characterId").textValue());
                assertEquals(p.hp(), wire.get("hp").doubleValue(), EPS); assertEquals(p.dead(), wire.get("dead").booleanValue());
                assertEquals(p.x(), wire.get("x").doubleValue(), EPS); assertEquals(p.y(), wire.get("y").doubleValue(), EPS);
                assertEquals(p.velocityX(), wire.get("velocityX").doubleValue(), EPS);
                assertEquals(p.velocityY(), wire.get("velocityY").doubleValue(), EPS);
                var nativeBody = motion(match).get(p.playerId());
                for (int i = 0; i < 4; i++) {
                    assertEquals(nativeBody.get(i), wire.get(new String[]{"x", "y", "velocityX", "velocityY"}[i]).doubleValue(), EPS);
                }
            }
            assertEquals(snapshot.projectiles().size(), payload.get("projectiles").size());
            for (int i = 0; i < snapshot.projectiles().size(); i++) {
                var projectile = snapshot.projectiles().get(i); var wire = payload.get("projectiles").get(i);
                assertEquals(projectile.projectileId(), wire.get("projectileId").longValue());
                assertEquals(match.getMatchId(), wire.get("matchId").textValue());
                assertEquals(projectile.ownerPlayerId().value(), wire.get("ownerPlayerId").textValue());
                assertEquals(projectile.projectileType(), wire.get("projectileType").textValue());
                assertEquals(projectile.spawnTick(), wire.get("spawnTick").longValue());
                assertEquals(projectile.x(), wire.get("x").doubleValue(), EPS); assertEquals(projectile.y(), wire.get("y").doubleValue(), EPS);
                assertEquals(projectile.velocityX(), wire.get("velocityX").doubleValue(), EPS);
                assertEquals(projectile.velocityY(), wire.get("velocityY").doubleValue(), EPS);
            }
            var history = client.received(); assertEquals(1, history.stream().filter(m -> m.type() == MessageType.GAME_START).count());
            long last = -1;
            for (var m : history) {
                if (m.type() == MessageType.GAME_STATE) {
                    long tick = m.payload().get("serverTick").longValue(); assertTrue(tick > last); last = tick;
                    assertEquals(id, m.payload().get("roomId").textValue());
                    assertTrue(history.indexOf(m) > history.indexOf(history.stream().filter(s -> s.type() == MessageType.GAME_START).findFirst().orElseThrow()));
                }
            }
        }
        return expected;
    }
    private void terminal(String id, List<TcpTestClient> roster) throws Exception {
        var match = runtime(id); var result = match.getMatchResult(); assertNotNull(result);
        assertEquals(RoomPhase.FINISHED, room(id).getPhase()); var finalState = state(id, roster);
        assertEquals("FINISHED", finalState.payload().get("phase").textValue()); ProtocolMessage expected = null;
        for (var client : roster) {
            var end = client.awaitType(MessageType.GAME_END);
            if (expected == null) { expected = end; } else { assertEquals(expected, end); }
            var payload = end.payload(); assertEquals(result.matchId(), payload.get("matchId").textValue());
            assertEquals(id, payload.get("roomId").textValue()); assertEquals(result.finishedAtTick(), payload.get("finishedAtTick").longValue());
            assertEquals(result.finishReason().name(), payload.get("finishReason").textValue());
            if (result.isDraw()) { assertTrue(payload.get("winnerId").isNull()); }
            else { assertEquals(result.winnerId().value(), payload.get("winnerId").textValue()); }
            assertEquals(result.participants().size(), payload.get("participants").size());
            for (var p : result.participants()) {
                var wire = payload.get("participants").get(p.slotIndex());
                assertEquals(p.playerId().value(), wire.get("playerId").textValue()); assertEquals(p.slotIndex(), wire.get("slotIndex").intValue());
                assertEquals(p.characterId(), wire.get("characterId").textValue()); assertEquals(p.finalHp(), wire.get("finalHp").doubleValue(), EPS);
                assertEquals(p.eliminated(), wire.get("eliminated").booleanValue()); assertEquals(p.displayName(), wire.get("displayName").textValue());
            }
            var history = client.received(); assertEquals(finalState, history.get(history.indexOf(end) - 1));
            assertEquals(1, history.stream().filter(m -> m.type() == MessageType.GAME_END).count());
        }
    }
    private void error(ProtocolMessage response, String code) {
        assertEquals(MessageType.ERROR, response.type()); assertEquals(code, response.payload().get("code").textValue());
    }

    @Test(timeout=30000) public void fourClientsCompleteMatchWithAuthoritativeMovementStompAbilityAndTimeout() throws Exception {
        var roster = roster("ROOM_A4", 4); launch(Map.of("ROOM_A4", roster)); var match = runtime("ROOM_A4");
        assertFalse(match.getJumpState(roster.get(2).playerId()).grounded());
        var before = match.getLatestSnapshot(); var bodies = motion(match);
        roster.get(0).inputAndBarrier(1, 1, false, false, false);
        roster.get(1).inputAndBarrier(1, -1, false, false, false);
        roster.get(2).inputAndBarrier(1, 0, false, true, false);
        var fourth = roster.get(3); fourth.send(new ProtocolMessage(MessageType.PLAYER_INPUT, null,
                fourth.input(1, 0, false, false, false).put("abilityPressed", true))); fourth.barrier();
        assertEquals(4, pending(match)); assertSame(before, match.getLatestSnapshot()); assertEquals(bodies, motion(match));
        cycles(2); var first = state("ROOM_A4", roster); assertEquals(0, pending(match));
        assertTrue(player(first, roster.get(0).playerId()).get("x").doubleValue() > bodies.get(roster.get(0).playerId()).get(0));
        assertTrue(player(first, roster.get(1).playerId()).get("x").doubleValue() < bodies.get(roster.get(1).playerId()).get(0));
        assertEquals(0, player(first, roster.get(2).playerId()).get("velocityX").doubleValue(), EPS);
        assertEquals(0, player(first, fourth.playerId()).get("velocityX").doubleValue(), EPS);
        assertEquals(100f - 0.1f, player(first, roster.get(2).playerId()).get("hp").doubleValue(), EPS);
        assertTrue(player(first, roster.get(2).playerId()).get("hp").isFloatingPointNumber());
        assertTrue(first.payload().get("projectiles").size() > 0);
        for (var projectile : first.payload().get("projectiles")) {
            assertEquals(fourth.playerId().value(), projectile.get("ownerPlayerId").textValue()); assertEquals("IBM", projectile.get("projectileType").textValue());
        }
        cycles(3); var second = state("ROOM_A4", roster); assertEquals(6, second.payload().get("serverTick").longValue());
        assertEquals(60, MatchRuntime.TICKS_PER_SECOND);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); cycles(1); assertEquals(AuthoritativeMatchResult.FinishReason.TIME_LIMIT, match.getMatchResult().finishReason());
        assertTrue(match.getMatchResult().isDraw()); terminal("ROOM_A4", roster);
        var result = match.getMatchResult(); var snapshot = match.getLatestSnapshot(); cycles(6);
        assertSame(result, match.getMatchResult()); assertSame(snapshot, match.getLatestSnapshot());
        for (var client : roster) { client.barrier(); client.assertQuiet(); }
    }

    @Test(timeout=30000) public void duplicateInvalidAndSpoofedInputsCannotControlOtherThreePlayers() throws Exception {
        var roster = roster("ROOM_A4", 4);
        assertEquals(MessageType.ROOM_STATE, roster.get(0).request(MessageType.JOIN_ROOM,
                ProtocolV1.object().put("roomId", "ROOM_A4")).type());
        assertEquals(4, room("ROOM_A4").getPlayerCount());
        var fifth = client(); error(fifth.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "ROOM_A4")), "ROOM_FULL");
        assertEquals(4, room("ROOM_A4").getPlayerCount()); launch(Map.of("ROOM_A4", roster));
        var match = runtime("ROOM_A4"); var before = motion(match); var first = roster.get(0);
        first.inputAndBarrier(1, 1, false, false, false);
        error(first.request(MessageType.PLAYER_INPUT, first.input(1, -1, false, false, false)), "DUPLICATE_INPUT");
        for (int i = 1; i < 4; i++) {
            error(first.request(MessageType.PLAYER_INPUT, first.input(2, -1, false, false, false).put("playerId", roster.get(i).playerId().value())), "UNAUTHORIZED_PLAYER");
        }
        error(first.request(MessageType.PLAYER_INPUT, first.input(2, 2, false, false, false)), "INVALID_PAYLOAD");
        assertEquals(1, pending(match)); assertEquals(before, motion(match)); cycles(2); var state = state("ROOM_A4", roster);
        assertTrue(player(state, first.playerId()).get("velocityX").doubleValue() > 0);
        for (int i = 1; i < 4; i++) { assertEquals(0, player(state, roster.get(i).playerId()).get("velocityX").doubleValue(), EPS); }
        assertEquals(0, pending(match)); assertTrue(server.isRunning()); assertFalse(match.isFinished()); fifth.assertQuiet();
    }

    @Test(timeout=30000) public void oneEliminationDoesNotEndFourPlayerMatchAndLastSurvivorWins() throws Exception {
        var roster = roster("ROOM_A4", 4); launch(Map.of("ROOM_A4", roster)); var match = runtime("ROOM_A4");
        // Fixture HP ban đầu: vẫn sống. Loại player và tạo result chỉ qua stomp TCP + production tick.
        for (int i = 1; i < 4; i++) {
            var client = roster.get(i);
            match.getMatchState().getPlayer(client.playerId()).applyDamage(99.95f);
            assertFalse(match.getMatchState().getPlayer(client.playerId()).isDead());
            if (match.getJumpState(client.playerId()).grounded()) { client.inputAndBarrier(0, 0, true, false, false); }
        }
        cycles(1);
        roster.get(1).inputAndBarrier(1, 0, false, true, false); cycles(1); state("ROOM_A4", roster);
        assertTrue(match.getMatchState().getPlayer(roster.get(1).playerId()).isDead()); assertNull(match.getMatchResult());
        assertEquals(RoomPhase.PLAYING, room("ROOM_A4").getPhase());
        for (int i = 2; i < 4; i++) { assertFalse(match.getJumpState(roster.get(i).playerId()).grounded()); roster.get(i).inputAndBarrier(1, 0, false, true, false); }
        cycles(1); assertEquals(roster.get(0).playerId(), match.getMatchResult().winnerId());
        assertEquals(AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING, match.getMatchResult().finishReason()); terminal("ROOM_A4", roster);
        var result = match.getMatchResult(); cycles(6); assertSame(result, match.getMatchResult());
        for (var client : roster) { client.barrier(); client.assertQuiet(); }
    }

    @Test(timeout=30000) public void twoRoomsKeepWorldsQueuesProjectilesDamageAndResultsIsolatedAfterDisconnect() throws Exception {
        var a = roster("ROOM_A4", 4); var b = roster("ROOM_B2", 2); launch(Map.of("ROOM_A4", a, "ROOM_B2", b));
        var ma = runtime("ROOM_A4"); var mb = runtime("ROOM_B2");
        assertNotSame(ma.getMatchSetup(), mb.getMatchSetup()); assertNotEquals(ma.getMatchId(), mb.getMatchId());
        assertNotSame(physics(ma).getWorld(), physics(mb).getWorld()); assertNotSame(field(ma, "inputQueue"), field(mb, "inputQueue"));
        var fourth = a.get(3); fourth.send(new ProtocolMessage(MessageType.PLAYER_INPUT, null,
                fourth.input(1, 0, false, false, false).put("abilityPressed", true))); fourth.barrier();
        a.get(2).inputAndBarrier(1, 0, false, true, false); assertEquals(2, pending(ma)); assertEquals(0, pending(mb));
        b.get(0).inputAndBarrier(1, 1, false, false, false); assertEquals(1, pending(mb));
        cycles(2); var sa = state("ROOM_A4", a); var sb = state("ROOM_B2", b);
        assertTrue(sa.payload().get("projectiles").size() > 0); assertEquals(0, sb.payload().get("projectiles").size());
        assertEquals(99.9f, player(sa, a.get(2).playerId()).get("hp").doubleValue(), EPS);
        for (var p : sb.payload().get("players")) { assertEquals(100, p.get("hp").doubleValue(), EPS); }
        a.get(3).close(); var recipients = a.subList(0, 3);
        // Fixture dọn điều kiện HP riêng room A; không ép result/phase hoặc gửi END từ test.
        for (int i = 1; i < 4; i++) { ma.getMatchState().getPlayer(a.get(i).playerId()).applyDamage(100); }
        cycles(1); terminal("ROOM_A4", recipients); assertEquals(a.get(0).playerId(), ma.getMatchResult().winnerId());
        assertEquals(RoomPhase.PLAYING, room("ROOM_B2").getPhase()); assertNull(mb.getMatchResult()); assertEquals(4, mb.getMatchState().getServerTick());
        cycles(2); state("ROOM_B2", b); assertEquals(6, mb.getMatchState().getServerTick());
        for (var client : b) {
            client.barrier(); assertFalse(client.received().stream().anyMatch(m -> m.type() == MessageType.GAME_END)); client.assertQuiet();
        }
        for (var client : recipients) { client.barrier(); client.assertQuiet(); }
        assertTrue(server.isRunning()); assertNull(server.getAcceptFailure());
        var wa = physics(ma); var wb = physics(mb);
        // FINISHED giữ World tới cleanup tường minh; xóa A không được dispose/tắt B.
        var removed = server.getRoomManager().removeRoom("ROOM_A4"); assertTrue(removed.isClosed());
        assertTrue(ma.isClosed()); assertTrue(wa.isDisposed()); assertNull(ma.getLatestSnapshot()); assertFalse(wb.isDisposed());
        cycles(3); state("ROOM_B2", b); assertEquals(9, mb.getMatchState().getServerTick());
        for (var client : b) { client.barrier(); client.assertQuiet(); }
        var sessions = server.getConnectionManager().getSessions();
        var workers = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("smashprofs-tcp-")).toList();
        server.stop(); assertTrue(ma.isClosed()); assertTrue(mb.isClosed()); assertTrue(wa.isDisposed()); assertTrue(wb.isDisposed());
        assertNull(ma.getLatestSnapshot()); assertNull(mb.getLatestSnapshot()); assertNull(mb.getMatchResult());
        assertTrue(executor.isTerminated()); assertEquals(0, server.getRoomManager().getRoomCount()); assertEquals(0, server.getConnectionManager().size());
        assertTrue(sessions.stream().noneMatch(s -> s.isConnected()));
        for (var client : recipients) { client.assertEof(); } for (var client : b) { client.assertEof(); }
        for (var worker : workers) { worker.join(5000); assertFalse("Worker còn sống: " + worker.getName(), worker.isAlive()); }
    }
}
