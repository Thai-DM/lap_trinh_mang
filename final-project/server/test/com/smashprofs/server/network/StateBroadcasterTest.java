package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.*;
import com.smashprofs.server.room.*;
import org.junit.After;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Cadence/lifecycle bằng native runtime và manual clock; fake chỉ tại non-blocking channel boundary. */
public class StateBroadcasterTest {
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final FixedTickScheduler scheduler = new FixedTickScheduler();
    private final RoomManager rooms = new RoomManager(scheduler, clock::get);
    private final ConnectionManager connections = new ConnectionManager();
    private final StateBroadcaster broadcaster = new StateBroadcaster(rooms, connections);
    private final PlayerId a = new PlayerId("a"), b = new PlayerId("b");
    private final RecordingChannel channel = new RecordingChannel();
    @After public void cleanup() { broadcaster.close(); rooms.shutdown(); scheduler.stop(); }
    private GameRoom playing() {
        var room = rooms.getOrCreateRoom("r"); room.joinPlayer(a); room.joinPlayer(b);
        room.selectCharacter(a, "Alex"); room.selectCharacter(b, "Leo"); room.setReady(a, true); room.setReady(b, true);
        assertTrue(room.startCountdown()); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); assertTrue(room.updateCountdown());
        assertNotNull(room.prepareMatch()); assertNotNull(room.startMatch());
        var session = new ClientSession("s", channel); session.bindPlayer(a); session.joinRoom("r"); connections.register(session);
        return room;
    }
    private void ticks(GameRoom room, int count) { for (int i = 0; i < count; i++) { room.getMatchRuntime().tick(); broadcaster.broadcast(); } }
    @Test public void broadcastRateDefaultsTo20Hz() { assertEquals(20, broadcaster.getSnapshotsPerSecond()); }
    @Test public void invalidBroadcastConfigurationIsRejected() {
        for (int rate : new int[]{-1,0,7,19,40,61}) { assertThrows(IllegalArgumentException.class, () -> new StateBroadcaster(rooms, connections, rate)); }
        for (int rate : new int[]{10,20,30,60}) { assertEquals(rate, new StateBroadcaster(rooms, connections, rate).getSnapshotsPerSecond()); }
    }
    @Test public void oneSnapshotIsSentEveryThreeTicks() {
        var room = playing(); ticks(room, 2); assertTrue(channel.deliveries.isEmpty()); ticks(room, 1);
        assertEquals(1, channel.deliveries.size()); assertEquals(3, channel.deliveries.get(0).snapshot().serverTick());
        ticks(room, 3); assertEquals(6, channel.deliveries.get(1).snapshot().serverTick());
    }
    @Test public void broadcastDoesNotDuplicateSameTick() {
        var room = playing(); ticks(room, 3); broadcaster.broadcast(); broadcaster.broadcast(); assertEquals(1, channel.deliveries.size());
    }
    @Test public void configuredRateChangesStrideWithoutChangingSimulationTickCount() {
        var room = playing(); var configured = new StateBroadcaster(rooms, connections, 30);
        try {
            for (int i = 0; i < 6; i++) { room.getMatchRuntime().tick(); configured.broadcast(); }
            assertEquals(List.of(2L,4L,6L), channel.deliveries.stream().map(d -> d.snapshot().serverTick()).toList());
            assertEquals(6, room.getMatchRuntime().getMatchState().getServerTick());
        } finally { configured.close(); }
    }
    @Test public void broadcasterDoesNotMutateMatchStateOrStepWorld() {
        var room = playing(); ticks(room, 3); var runtime = room.getMatchRuntime(); var cached = runtime.getLatestSnapshot();
        for (int i = 0; i < 30; i++) { broadcaster.broadcast(); }
        assertSame(cached, runtime.getLatestSnapshot()); assertEquals(3, runtime.getMatchState().getServerTick());
    }
    @Test public void gameStateUsesImmutableSnapshot() {
        var room = playing(); ticks(room, 3); var delivery = channel.deliveries.get(0);
        assertThrows(UnsupportedOperationException.class, () -> delivery.snapshot().players().clear());
        assertThrows(UnsupportedOperationException.class, () -> delivery.snapshot().projectiles().clear());
        ticks(room, 3); assertEquals(3, delivery.snapshot().serverTick()); assertEquals(6, room.getMatchRuntime().getLatestSnapshot().serverTick());
    }
    @Test public void authoritativeRemainingTimeUsesRuntimeClockNotClientTick() {
        var room = playing(); clock.addAndGet(TimeUnit.SECONDS.toNanos(7)); ticks(room, 3);
        assertEquals(113_000, channel.deliveries.get(0).remainingTimeMs());
    }
    @Test public void nonPlayingRoomDoesNotBroadcast() {
        var room = rooms.getOrCreateRoom("r"); broadcaster.broadcast(); assertTrue(channel.deliveries.isEmpty());
        room.joinPlayer(a); room.joinPlayer(b); room.selectCharacter(a, "Alex"); room.selectCharacter(b, "Leo");
        room.setReady(a, true); room.setReady(b, true); broadcaster.broadcast(); room.startCountdown(); broadcaster.broadcast();
        assertTrue(channel.deliveries.isEmpty()); assertNull(room.getMatchRuntime());
    }
    @Test public void finishedRoomStopsRegularBroadcastAndOffersTerminalOnce() {
        var room = playing(); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); ticks(room, 1);
        assertEquals(RoomPhase.FINISHED, room.getPhase()); assertEquals(1, channel.deliveries.size());
        assertEquals("FINISHED", channel.deliveries.get(0).phase()); assertEquals(0, channel.deliveries.get(0).remainingTimeMs());
        for (int i = 0; i < 10; i++) { broadcaster.broadcast(); } assertEquals(1, channel.deliveries.size());
    }
    @Test public void gameEndIsNotSentBeforeMatchFinished() {
        var room = playing(); ticks(room, 3); broadcaster.broadcast(); assertTrue(channel.ends.isEmpty());
        broadcaster.close(); assertTrue(channel.ends.isEmpty()); assertNull(room.getMatchRuntime().getMatchResult());
    }
    @Test public void startIsOfferedOnceAtFirstCycleBeforeSnapshotCadenceWithoutExtraTick() {
        var room = playing(); ticks(room, 1); assertEquals(1, channel.starts.size()); assertTrue(channel.deliveries.isEmpty());
        assertSame(room.getMatchRuntime().getMatchSetup(), channel.starts.get(0).setup());
        for (int i = 0; i < 20; i++) { broadcaster.broadcast(); }
        assertEquals(1, channel.starts.size()); assertEquals(1, room.getMatchRuntime().getMatchState().getServerTick());
        assertTrue(channel.starts.get(0).stillValid().getAsBoolean()); broadcaster.close();
        assertFalse(channel.starts.get(0).stillValid().getAsBoolean());
    }
    @Test public void gameEndIsEnqueuedOncePerSessionWithoutSteppingPhysics() {
        var room = playing(); var second = new RecordingChannel(); var s = new ClientSession("second", second);
        s.bindPlayer(b); s.joinRoom("r"); connections.register(s);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); ticks(room, 1); var runtime = room.getMatchRuntime();
        var snapshot = runtime.getLatestSnapshot(); var result = runtime.getMatchResult();
        for (int i = 0; i < 50; i++) { room.getPhase(); broadcaster.broadcast(); }
        assertEquals(1, channel.ends.size()); assertEquals(1, second.ends.size());
        assertSame(result, channel.ends.get(0).result()); assertSame(result, second.ends.get(0).result());
        assertSame(snapshot, channel.ends.get(0).finalState().snapshot()); assertSame(snapshot, runtime.getLatestSnapshot());
        assertEquals(1, runtime.getMatchState().getServerTick()); assertSame(result, runtime.getMatchResult());
    }
    @Test public void gameEndExcludesUnregisteredAndForeignSessions() {
        var room = playing(); var outsider = new RecordingChannel(); var session = new ClientSession("outsider", outsider);
        session.bindPlayer(new PlayerId("unknown")); session.joinRoom("r"); connections.register(session);
        connections.remove("s"); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); ticks(room, 1);
        assertTrue(channel.ends.isEmpty()); assertTrue(outsider.ends.isEmpty());
    }
    @Test public void shutdownInvalidatesQueuedGameEndWithoutChangingResult() {
        var room = playing(); clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); ticks(room, 1);
        var end = channel.ends.get(0); broadcaster.close(); assertFalse(end.finalState().stillValid().getAsBoolean());
        assertSame(end.result(), room.getMatchRuntime().getMatchResult());
    }
    @Test public void disconnectedSessionDoesNotReceiveNewFrames() {
        var room = playing(); ticks(room, 3); connections.get("s").close(); ticks(room, 3); assertEquals(1, channel.deliveries.size());
        assertFalse(channel.deliveries.get(0).stillValid().getAsBoolean());
    }
    @Test public void unjoinedAndForeignPlayerSessionsAreExcluded() {
        var room = playing(); var other = new RecordingChannel(); var session = new ClientSession("other", other);
        session.bindPlayer(new PlayerId("intruder")); session.joinRoom("r"); connections.register(session);
        ticks(room, 3); assertTrue(other.deliveries.isEmpty()); session.bindPlayer(b); session.leaveRoom(); ticks(room, 3);
        assertTrue(other.deliveries.isEmpty());
    }
    @Test public void disposedRuntimeAndDeletedRoomInvalidateQueuedSnapshot() {
        var room = playing(); ticks(room, 3); var delivery = channel.deliveries.get(0);
        rooms.removeRoom("r"); broadcaster.broadcast(); assertFalse(delivery.stillValid().getAsBoolean()); assertEquals(1, channel.deliveries.size());
    }
    @Test public void changedTrustedBindingInvalidatesPreviouslyQueuedRoomSnapshot() {
        var room = playing(); ticks(room, 3); var delivery = channel.deliveries.get(0);
        connections.get("s").joinRoom("another-room"); assertFalse(delivery.stillValid().getAsBoolean());
        ticks(room, 3); assertEquals(1, channel.deliveries.size());
    }
    @Test public void stoppingBroadcasterInvalidatesPendingDeliveries() {
        var room = playing(); ticks(room, 3); broadcaster.close(); ticks(room, 3);
        assertEquals(1, channel.deliveries.size()); assertFalse(channel.deliveries.get(0).stillValid().getAsBoolean());
    }
    private GameStateDelivery sample() {
        var p1 = new AuthoritativeGameSnapshot.PlayerSnapshot(a, 0, "Alex", 1,2,3,4,99.5f,true,false,false,true);
        var p2 = new AuthoritativeGameSnapshot.PlayerSnapshot(b, 1, "Leo", 5,6,7,8,91.25f,false,true,false,false);
        return new GameStateDelivery(new AuthoritativeGameSnapshot("m", "r", 120, List.of(p2,p1), List.of()), "PLAYING", 118000, () -> true);
    }
    @Test public void gameStateUsesProtocolV1EnvelopeAndCorrectFraming() throws Exception {
        var message = GameStateMessageMapper.map(sample()); assertEquals(1, message.version()); assertEquals(MessageType.GAME_STATE, message.type());
        assertNull(message.requestId()); byte[] frame = new JsonFrameCodec().encode(message);
        assertEquals(frame.length - 4, ByteBuffer.wrap(frame).getInt());
        var decoded = new JsonFrameCodec().readNext(new ByteArrayInputStream(frame));
        assertEquals(message.type(), decoded.type()); assertEquals(message.version(), decoded.version()); assertNull(decoded.requestId());
        // JSON number decode là DoubleNode, mapper giữ FloatNode: so wire bytes, không so kiểu node Java.
        assertArrayEquals(frame, new JsonFrameCodec().encode(decoded));
    }
    @Test public void gameStatePreservesFloatHP() {
        var players = GameStateMessageMapper.map(sample()).payload().get("players");
        assertEquals(99.5, players.get(0).get("hp").doubleValue(), 0); assertEquals(91.25, players.get(1).get("hp").doubleValue(), 0);
    }
    @Test public void gameStateContainsCorrectIdsServerTickAndDeterministicPlayerOrder() {
        var payload = GameStateMessageMapper.map(sample()).payload(); assertEquals("m", payload.get("matchId").textValue());
        assertEquals("r", payload.get("roomId").textValue()); assertEquals(120, payload.get("serverTick").longValue());
        assertEquals("a", payload.get("players").get(0).get("playerId").textValue());
        assertEquals("b", payload.get("players").get(1).get("playerId").textValue());
        assertEquals(0, payload.get("players").get(0).get("slotIndex").intValue());
    }
    @Test public void mapperDoesNotMutateSnapshotOrExposeResources() {
        var delivery = sample(); GameStateMessageMapper.map(delivery); assertEquals(b, delivery.snapshot().players().get(0).playerId());
        var payload = GameStateMessageMapper.map(delivery).payload(); assertEquals(Set.of("matchId","roomId","serverTick","phase","remainingTimeMs","players","projectiles"),
                new HashSet<>(iterableFields(payload)));
    }
    @Test public void gameStateProjectileOrderIsDeterministic() {
        var sample = sample();
        var later = new com.smashprofs.server.game.projectile.ServerProjectile.View(9,"m",a,"ROCKET",1,1,2,3,4);
        var earlier = new com.smashprofs.server.game.projectile.ServerProjectile.View(2,"m",b,"STAR",1,5,6,7,8);
        var snapshot = new AuthoritativeGameSnapshot("m","r",120,sample.snapshot().players(),List.of(later,earlier));
        var payload = GameStateMessageMapper.map(new GameStateDelivery(snapshot,"PLAYING",118000,()->true)).payload();
        assertEquals(2,payload.get("projectiles").get(0).get("projectileId").longValue());
        assertEquals(9,payload.get("projectiles").get(1).get("projectileId").longValue());
        assertEquals(9,snapshot.projectiles().get(0).projectileId()); // Không sort mutable collection gốc.
    }
    private List<String> iterableFields(com.fasterxml.jackson.databind.node.ObjectNode node) {
        List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); return names;
    }
    @Test public void oversizedPayloadIsRejectedSafely() {
        var message = GameStateMessageMapper.map(sample());
        assertThrows(IOException.class, () -> new JsonFrameCodec().encode(message, 128));
    }
    private static final class RecordingChannel implements SessionChannel {
        final List<GameStateDelivery> deliveries = new ArrayList<>(); boolean open = true;
        final List<GameEndDelivery> ends = new ArrayList<>();
        final List<GameStartDelivery> starts = new ArrayList<>();
        @Override public boolean offerGameStart(GameStartDelivery delivery) { starts.add(delivery); return true; }
        @Override public boolean offerGameEnd(GameEndDelivery delivery) {
            if (!open) { return false; } ends.add(delivery); deliveries.add(delivery.finalState()); return true;
        }
        @Override public boolean offerGameState(GameStateDelivery delivery) { if (!open) { return false; } deliveries.add(delivery); return true; }
        @Override public void send(Object message) { fail("Simulation không được dùng send đồng bộ"); }
        @Override public void close() { open = false; }
        @Override public boolean isOpen() { return open; }
    }
}
