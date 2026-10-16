package com.smashprofs.game.network.client;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.*;
import com.smashprofs.server.game.projectile.ServerProjectile;
import com.smashprofs.server.network.*;
import org.junit.Test;
import java.io.File;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Pure presentation tests (no OpenGL proof). Fixtures use the actual v1 server mappers. */
public class OnlinePresentationTest {
    private static final String[] CHARACTERS = {"Alex", "Leo", "Luca", "Viktor"};
    private static GameStartRoster roster(int count) {
        List<GameStartRoster.Participant> values = new ArrayList<>();
        for (int i = 0; i < count; i++) { values.add(new GameStartRoster.Participant(new PlayerId("opaque-" + i), i, CHARACTERS[i])); }
        return new GameStartRoster("match", "arena", values);
    }
    private static ProtocolMessage state(int count, long tick, float offset, float hp, boolean dead,
                                         String phase, List<ServerProjectile.View> projectiles) {
        List<AuthoritativeGameSnapshot.PlayerSnapshot> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(new AuthoritativeGameSnapshot.PlayerSnapshot(new PlayerId("opaque-" + i), i, CHARACTERS[i],
                    offset + i * 0.25f, 1.25f, 0.5f, -0.1f, hp, true, i == 1, dead, i != 1));
        }
        return GameStateMessageMapper.map(new GameStateDelivery(new AuthoritativeGameSnapshot("match", "arena", tick, values, projectiles),
                phase, phase.equals("FINISHED") ? 0 : 119000, () -> true));
    }
    private static ProtocolMessage end(int count, long tick, String winner, String reason, float hp, boolean dead) {
        List<AuthoritativeMatchResult.ParticipantResult> values = new ArrayList<>();
        for (int i = 0; i < count; i++) { values.add(new AuthoritativeMatchResult.ParticipantResult(new PlayerId("opaque-" + i), i, CHARACTERS[i], hp, dead)); }
        return GameEndMessageMapper.map(new AuthoritativeMatchResult("match", "arena", tick,
                winner == null ? null : new PlayerId(winner), AuthoritativeMatchResult.FinishReason.valueOf(reason), values));
    }
    private static ServerProjectile.View projectile(long id, float x) {
        return new ServerProjectile.View(id, "match", new PlayerId("opaque-0"), "ROCKET", 1, x, 1, 0.5f, -0.2f);
    }
    private static final class Fixture {
        final AtomicLong clock = new AtomicLong();
        final ValueTransport network;
        final ClientMatchSession session;
        final StateInterpolator.V1 interpolator;
        final OnlineMatchPresentation view;
        Fixture(int count, int localSlot) {
            network = new ValueTransport(new PlayerId("opaque-" + localSlot));
            session = new ClientMatchSession(network, roster(count), network.getPlayerId(), clock::get);
            interpolator = new StateInterpolator.V1(session.getSnapshotBuffer(), 6, 1, clock::get);
            view = new OnlineMatchPresentation(session, interpolator);
        }
        void receive(ProtocolMessage message) { assertTrue(session.acceptState(message)); view.update(); }
    }
    private static final class ValueTransport extends NetworkClient {
        final PlayerId id; int inputs;
        ValueTransport(PlayerId id) { super("127.0.0.1", 0, null); this.id = id; }
        @Override public PlayerId getPlayerId() { return id; }
        @Override public boolean sendMessage(ProtocolMessage message) { inputs++; return true; }
    }

    @Test public void twoThreeFourPlayersMapLocalByIdentityIncludingNonzeroSlotAndPreserveFloatHP() {
        for (int count = 2; count <= 4; count++) {
            var f = new Fixture(count, count - 1);
            f.receive(state(count, 3, 2.125f, 92.75f, false, "PLAYING", List.of()));
            assertEquals(count, f.view.getPlayers().size());
            assertEquals(1, f.view.getPlayers().stream().filter(OnlineMatchPresentation.PlayerView::local).count());
            for (int i = 0; i < count; i++) {
                var p = f.view.getPlayers().get(i); assertEquals(i == count - 1, p.local());
                assertEquals("opaque-" + i, p.state().playerId()); assertEquals(i, p.state().slotIndex());
                assertEquals(CHARACTERS[i], p.state().characterId()); assertEquals(2.125f + i * 0.25f, p.state().x(), 0);
                assertEquals(92.75f, p.state().hp(), 0); assertEquals(i == 1, p.state().blocking()); assertEquals(i != 1, p.state().facingRight());
            }
        }
    }

    @Test public void interpolationAnchorsTo60HzTicksWith100msDelayAndWorksIndependentlyOfSamplingFPS() {
        var f = new Fixture(2, 1);
        f.receive(state(2, 3, 0, 100, false, "PLAYING", List.of()));
        f.receive(state(2, 6, 0.3f, 99.5f, false, "PLAYING", List.of()));
        f.receive(state(2, 9, 0.6f, 92.75f, false, "PLAYING", List.of()));
        assertEquals(6, f.interpolator.getDelayTicks(), 0);
        f.clock.set(25_000_000); f.view.update();
        assertEquals(4.5, f.view.getFrame().renderTick(), 0.00001); assertEquals(0.15f, f.view.getPlayers().get(0).state().x(), 0.00001);
        assertEquals(92.75f, f.view.getPlayers().get(0).state().hp(), 0); // latest authoritative HP, never interpolated/rounded
        var frame = f.view.getFrame();
        for (int i = 0; i < 144; i++) { f.view.update(); assertEquals(frame, f.view.getFrame()); }
        f.clock.set(50_000_000); f.view.update();
        assertEquals(6, f.view.getFrame().renderTick(), 0.00001); assertEquals(0.3f, f.view.getPlayers().get(0).state().x(), 0.00001);
        assertEquals(0.6f, f.session.getSnapshotBuffer().getLatestSnapshot().players().get(0).x(), 0); // authoritative value untouched
    }

    @Test public void duplicateAndStaleSnapshotsDoNotReplacePresentedStateOrAdvanceArrivalAnchor() {
        var f = new Fixture(2, 0); f.receive(state(2, 3, 2, 92.75f, false, "PLAYING", List.of()));
        var frame = f.view.getFrame();
        assertFalse(f.session.acceptState(state(2, 3, 8, 0, true, "PLAYING", List.of())));
        assertFalse(f.session.acceptState(state(2, 2, 8, 0, true, "PLAYING", List.of())));
        f.view.update(); assertEquals(frame, f.view.getFrame()); assertEquals(0, f.session.getMetrics().getDroppedSnapshots());
    }

    @Test public void starvationHoldsAtLatestAndLateArrivalsNeverRewindRenderTick() {
        var f = new Fixture(2, 0); f.receive(state(2, 3, 0, 100, false, "PLAYING", List.of()));
        f.receive(state(2, 6, 0.3f, 100, false, "PLAYING", List.of()));
        f.clock.set(5_000_000_000L); f.view.update();
        assertEquals(6, f.view.getFrame().renderTick(), 0); assertEquals(0.3f, f.view.getPlayers().get(0).state().x(), 0);
        f.receive(state(2, 9, 0.6f, 100, false, "PLAYING", List.of()));
        assertEquals(6, f.view.getFrame().renderTick(), 0);
        f.clock.addAndGet(10_000_000_000L); f.view.update();
        assertEquals(9, f.view.getFrame().renderTick(), 0); assertEquals(0.6f, f.view.getPlayers().get(0).state().x(), 0);
    }

    @Test public void teleportDeathAndRevivalSnapshotsSnapInsteadOfSlidingThroughMap() {
        var f = new Fixture(2, 0); f.receive(state(2, 3, 0, 100, false, "PLAYING", List.of()));
        f.receive(state(2, 6, 5, 100, false, "PLAYING", List.of()));
        f.receive(state(2, 9, 5, 100, false, "PLAYING", List.of()));
        f.clock.set(25_000_000); f.view.update(); assertEquals(5, f.view.getPlayers().get(0).state().x(), 0);
        f.receive(state(2, 12, 7, 0, true, "PLAYING", List.of()));
        assertTrue(f.view.getPlayers().get(0).state().dead()); assertEquals(7, f.view.getPlayers().get(0).state().x(), 0);
        f.receive(state(2, 15, 2, 100, false, "PLAYING", List.of()));
        assertFalse(f.view.getPlayers().get(0).state().dead());
        assertEquals(2, f.view.getPlayers().get(0).state().x(), 0); // changed life + discontinuity
    }

    @Test public void authoritativeProjectilesAreCreatedUpdatedAndRemovedByIdWithoutLocalGameplay() {
        var f = new Fixture(2, 0);
        f.receive(state(2, 3, 0, 100, false, "PLAYING", List.of(projectile(11, 1))));
        assertEquals(Set.of(11L), f.view.getProjectiles().keySet()); assertEquals(1, f.view.getProjectiles().get(11L).x(), 0);
        f.receive(state(2, 6, 0, 100, false, "PLAYING", List.of(projectile(11, 1.3f), projectile(22, 2))));
        assertEquals(Set.of(11L, 22L), f.view.getProjectiles().keySet());
        f.clock.set(1_000_000_000); f.view.update(); assertEquals(1.3f, f.view.getProjectiles().get(11L).x(), 0);
        f.receive(state(2, 9, 0, 100, false, "PLAYING", List.of(projectile(22, 2.3f))));
        assertEquals(Set.of(22L), f.view.getProjectiles().keySet());
        f.receive(state(2, 12, 0, 100, false, "PLAYING", List.of())); assertTrue(f.view.getProjectiles().isEmpty());
        assertEquals(0, f.network.inputs); // presentation never spawns/sends gameplay
    }

    @Test public void realMapperResultsPreserveWinnerOrDrawAndDuplicateEndTransitionsOnlyOnce() throws Exception {
        for (int count = 2; count <= 4; count++) {
            for (String reason : List.of("TIME_LIMIT", "LAST_PLAYER_STANDING", "ALL_PLAYERS_ELIMINATED")) {
                var f = new Fixture(count, count - 1);
                boolean draw = !reason.equals("LAST_PLAYER_STANDING");
                f.receive(state(count, 12, 2, 92.75f, false, "FINISHED", List.of()));
                var envelope = end(count, 12, draw ? null : "opaque-" + (count - 1), reason, 92.75f, false);
                var codec = new JsonFrameCodec(); envelope = codec.readNext(new java.io.ByteArrayInputStream(codec.encode(envelope)));
                assertTrue(f.session.acceptEnd(envelope));
                var result = f.view.takeResultTransition(); assertNotNull(result); assertEquals(draw, result.isDraw());
                assertEquals(count, result.participants().size()); assertEquals(92.75f, result.participants().get(0).finalHp(), 0);
                if (draw) { assertNull(result.winner()); } else { assertEquals("opaque-" + (count - 1), result.winner().playerId()); }
                assertFalse(f.session.acceptEnd(envelope)); assertNull(f.view.takeResultTransition());
                assertFalse(f.session.recordFrameInput(1, true, true, true, true, true));
                assertEquals(0, f.session.getPendingInputCount()); assertEquals(0, f.network.inputs);
                assertThrows(UnsupportedOperationException.class, () -> result.participants().clear());
            }
        }
    }

    @Test public void wrongMatchRoomRosterAndFinalSnapshotCannotPresentAResult() {
        var f = new Fixture(2, 0);
        assertThrows(IllegalArgumentException.class, () -> f.session.acceptEnd(end(2, 12, null, "TIME_LIMIT", 100, false)));
        f.receive(state(2, 12, 2, 92.75f, false, "FINISHED", List.of()));
        for (String field : List.of("matchId", "roomId")) {
            var payload = end(2, 12, null, "TIME_LIMIT", 92.75f, false).payload().put(field, "other");
            assertThrows(IllegalArgumentException.class, () -> f.session.acceptEnd(new ProtocolMessage(MessageType.GAME_END, null, payload)));
        }
        assertThrows(IllegalArgumentException.class, () -> f.session.acceptEnd(end(3, 12, null, "TIME_LIMIT", 92.75f, false)));
        assertThrows(IllegalArgumentException.class, () -> f.session.acceptEnd(end(2, 13, null, "TIME_LIMIT", 92.75f, false)));
        assertThrows(IllegalArgumentException.class, () -> f.session.acceptEnd(end(2, 12, null, "TIME_LIMIT", 100, false)));
        assertNull(f.view.takeResultTransition()); assertNull(f.session.getResult());
    }

    @Test public void malformedResultFieldsAreRejectedWithoutCoercionOrInventedWinner() {
        List<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> mutations = List.of(
                p -> p.put("winnerId", "outsider"), p -> p.remove("winnerId"), p -> p.put("winnerId", 1),
                p -> p.put("finishedAtTick", 12.0), p -> p.put("finishedAtTick", -1), p -> p.put("finishReason", "UNKNOWN"),
                p -> p.put("extra", true), p -> ((com.fasterxml.jackson.databind.node.ObjectNode)p.get("participants").get(0)).put("finalHp", "92.75"),
                p -> ((com.fasterxml.jackson.databind.node.ObjectNode)p.get("participants").get(0)).put("finalHp", 101),
                p -> ((com.fasterxml.jackson.databind.node.ObjectNode)p.get("participants").get(0)).put("eliminated", 0),
                p -> ((com.fasterxml.jackson.databind.node.ObjectNode)p.get("participants").get(1)).put("slotIndex", 0));
        for (var change : mutations) {
            var p = end(2, 12, null, "TIME_LIMIT", 92.75f, false).payload(); change.accept(p);
            assertThrows(IllegalArgumentException.class, () -> WireMatchResult.parse(p));
        }
    }

    @Test public void closingPresentationDoesNotCloseSessionAndPreventsLateResultTransition() {
        var f = new Fixture(2, 0); f.receive(state(2, 3, 2, 100, false, "PLAYING", List.of()));
        f.view.close(); assertTrue(f.session.isActive()); assertTrue(f.view.getPlayers().isEmpty());
        assertTrue(f.session.recordFrameInput(0, false, false, false, false, false));
        assertTrue(f.session.acceptState(state(2, 12, 2, 100, false, "FINISHED", List.of())));
        assertTrue(f.session.acceptEnd(end(2, 12, null, "TIME_LIMIT", 100, false)));
        f.view.update(); assertNull(f.view.takeResultTransition()); assertNull(f.view.getFrame());
    }

    @Test public void visualCatalogUsesExistingOriginalAnimationAndProjectileAssets() throws Exception {
        for (String character : GameStartRoster.CHARACTERS) {
            var assets = OnlineVisualCatalog.character(character);
            var stand = ImageIO.read(new File("../assets", assets.stand())); var run = ImageIO.read(new File("../assets", assets.run()));
            assertTrue(stand.getWidth() >= 400 && stand.getHeight() >= 100);
            assertTrue(run.getWidth() >= 600 && run.getHeight() >= 100);
        }
        for (String type : List.of("IBM", "STAR", "MINE", "ROCKET")) {
            assertNotNull(ImageIO.read(new File("../assets", OnlineVisualCatalog.projectile(type).path())));
        }
        assertTrue(OnlineVisualCatalog.projectile("ROCKET").orientFromVelocity());
        assertThrows(IllegalArgumentException.class, () -> OnlineVisualCatalog.character("invented"));
        assertThrows(IllegalArgumentException.class, () -> OnlineVisualCatalog.projectile("invented"));
    }
}
