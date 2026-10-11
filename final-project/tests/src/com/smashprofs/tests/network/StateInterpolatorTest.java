package com.smashprofs.tests.network;

import com.smashprofs.common.model.GameSnapshot;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerState;
import com.smashprofs.game.network.client.SnapshotBuffer;
import com.smashprofs.game.network.client.StateInterpolator;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class StateInterpolatorTest {

    private SnapshotBuffer buffer;
    private StateInterpolator interpolator;
    private PlayerId p1Id;
    private PlayerId p2Id;

    @Before
    public void setUp() {
        buffer = new SnapshotBuffer(10);
        interpolator = new StateInterpolator(buffer, 30.0f, 2.0f);
        p1Id = new PlayerId("player-1");
        p2Id = new PlayerId("player-2");
    }

    private GameSnapshot createSnapshot(long tick, float p1X, float p1Y, float p2X, float p2Y) {
        PlayerState p1 = new PlayerState(p1Id, "Alex", p1X, p1Y, 1f, 0f, 100, true, false, false, true);
        PlayerState p2 = new PlayerState(p2Id, "Leo", p2X, p2Y, -1f, 0f, 90, true, false, false, false);
        return new GameSnapshot("room-test", tick, "PLAYING", 45000L, List.of(p1, p2));
    }

    @Test
    public void testLerpPositionsAtExactFractions() {
        PlayerState p0 = new PlayerState(p1Id, "Alex", 10.0f, 20.0f, 2.0f, 0.0f, 100, true, false, false, true);
        PlayerState p1 = new PlayerState(p1Id, "Alex", 14.0f, 28.0f, 2.0f, 0.0f, 100, true, false, false, true);

        // Alpha = 0.5 (Halfway)
        PlayerState mid = StateInterpolator.interpolatePlayer(p0, p1, 0.5f);
        assertEquals(12.0f, mid.x(), 0.0001f);
        assertEquals(24.0f, mid.y(), 0.0001f);

        // Alpha = 0.25 (Quarter)
        PlayerState quarter = StateInterpolator.interpolatePlayer(p0, p1, 0.25f);
        assertEquals(11.0f, quarter.x(), 0.0001f);
        assertEquals(22.0f, quarter.y(), 0.0001f);

        // Alpha = 0.75 (Three-quarters)
        PlayerState threeQuarter = StateInterpolator.interpolatePlayer(p0, p1, 0.75f);
        assertEquals(13.0f, threeQuarter.x(), 0.0001f);
        assertEquals(26.0f, threeQuarter.y(), 0.0001f);
    }

    @Test
    public void testTeleportThresholdSnapsPosition() {
        // Distance between (0, 0) and (100, 100) is approx 141.4 > 20.0f (threshold)
        PlayerState p0 = new PlayerState(p1Id, "Alex", 0.0f, 0.0f, 0f, 0f, 100, true, false, false, true);
        PlayerState p1 = new PlayerState(p1Id, "Alex", 100.0f, 100.0f, 0f, 0f, 100, true, false, false, true);

        PlayerState result = StateInterpolator.interpolatePlayer(p0, p1, 0.5f);

        // Must snap immediately to p1 position, not midpoint (50, 50)
        assertEquals(100.0f, result.x(), 0.0001f);
        assertEquals(100.0f, result.y(), 0.0001f);
    }

    @Test
    public void testRenderTickAdvancesContinuouslyWithDelta() {
        buffer.pushSnapshot(createSnapshot(10L, 0f, 0f, 0f, 0f));

        // First update initializes renderTick = latestTick - delay = 10 - 2 = 8.0f
        interpolator.update(0.0f);
        assertTrue(interpolator.isInitialized());
        assertEquals(8.0f, interpolator.getRenderTick(), 0.0001f);

        // Advance by 1 full tick at 30Hz: delta = 1/30 = 0.0333s -> renderTick advances +1.0
        interpolator.update(1.0f / 30.0f);
        assertEquals(9.0f, interpolator.getRenderTick(), 0.0001f);

        // Advance by fractional tick (half tick): delta = 0.5 / 30 -> renderTick advances +0.5
        interpolator.update(0.5f / 30.0f);
        assertEquals(9.5f, interpolator.getRenderTick(), 0.0001f);
    }

    @Test
    public void testMultiplePlayersInterpolatedSimultaneously() {
        // Tick 10: P1 at (0, 0), P2 at (100, 100)
        buffer.pushSnapshot(createSnapshot(10L, 0.0f, 0.0f, 100.0f, 100.0f));
        // Tick 20: P1 at (10, 0), P2 at (80, 100)
        buffer.pushSnapshot(createSnapshot(20L, 10.0f, 0.0f, 80.0f, 100.0f));

        // Initialize at tick 18 (renderTick = 20 - 2 = 18.0f)
        interpolator.update(0.0f);
        assertEquals(18.0f, interpolator.getRenderTick(), 0.0001f);

        // At tick 18 between 10 and 20: alpha = (18 - 10) / 10 = 0.8
        Map<PlayerId, PlayerState> players = interpolator.getInterpolatedPlayers();
        assertNotNull(players);
        assertEquals(2, players.size());

        PlayerState p1 = players.get(p1Id);
        PlayerState p2 = players.get(p2Id);

        // P1: 0 + 0.8 * (10 - 0) = 8.0
        assertEquals(8.0f, p1.x(), 0.0001f);
        // P2: 100 + 0.8 * (80 - 100) = 84.0
        assertEquals(84.0f, p2.x(), 0.0001f);
    }

    @Test
    public void testDiscreteFlagsSwitchAtMidpoint() {
        PlayerState p0 = new PlayerState(p1Id, "Alex", 0f, 0f, 0f, 0f, 100, true, false, false, true);
        PlayerState p1 = new PlayerState(p1Id, "Alex", 10f, 0f, 0f, 0f, 80, false, true, false, false);

        // Before midpoint (alpha = 0.4f) -> retains p0 discrete states
        PlayerState early = StateInterpolator.interpolatePlayer(p0, p1, 0.4f);
        assertTrue(early.grounded());
        assertFalse(early.blocking());
        assertTrue(early.facingRight());
        assertEquals(100, early.hp());

        // At/after midpoint (alpha = 0.6f) -> switches to p1 discrete states
        PlayerState late = StateInterpolator.interpolatePlayer(p0, p1, 0.6f);
        assertFalse(late.grounded());
        assertTrue(late.blocking());
        assertFalse(late.facingRight());
        assertEquals(80, late.hp());
    }

    @Test
    public void testResetAndReinitialization() {
        buffer.pushSnapshot(createSnapshot(10L, 0f, 0f, 0f, 0f));
        interpolator.update(0.0f);
        assertTrue(interpolator.isInitialized());

        interpolator.reset();
        assertFalse(interpolator.isInitialized());
        assertEquals(-1.0f, interpolator.getRenderTick(), 0.0001f);
        assertTrue(interpolator.getInterpolatedPlayers().isEmpty());
    }
}
