package com.smashprofs.game.network.client;

import com.smashprofs.common.model.GameSnapshot;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerState;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mathematical interpolation engine for client-side player rendering.
 * <p>
 * Key architectural features:
 * 1. Operates on the 60 FPS Render Thread independently from OpenGL / LibGDX graphics context (Pure POJO).
 * 2. Continuous real-valued renderTick accumulator: renderTick += deltaSeconds * serverTickRate.
 * 3. Linear interpolation (Lerp) for continuous vectors: x, y, velocityX, velocityY.
 * 4. Midpoint latching for discrete state flags (grounded, blocking, dead, facingRight, hp).
 * 5. Teleport threshold protection: Snaps destination immediately when displacement exceeds threshold
 *    to prevent visual sliding on respawns / stage transitions.
 * </p>
 */
public class StateInterpolator {

    public static final float DEFAULT_TICK_RATE = 30.0f; // Standard server broadcast rate (30 snapshots/sec)
    public static final float DEFAULT_INTERPOLATION_DELAY_TICKS = 2.0f; // 2 tick buffer delay (~66ms)
    public static final float TELEPORT_DISTANCE_THRESHOLD = 20.0f; // In physics units; snaps if distance exceeds this

    private final SnapshotBuffer buffer;
    private final float serverTickRate;
    private final float interpolationDelayTicks;

    private float renderTick = -1.0f;
    private boolean initialized = false;

    public StateInterpolator(SnapshotBuffer buffer) {
        this(buffer, DEFAULT_TICK_RATE, DEFAULT_INTERPOLATION_DELAY_TICKS);
    }

    public StateInterpolator(SnapshotBuffer buffer, float serverTickRate, float interpolationDelayTicks) {
        if (buffer == null) {
            throw new IllegalArgumentException("SnapshotBuffer cannot be null");
        }
        this.buffer = buffer;
        this.serverTickRate = Math.max(1.0f, serverTickRate);
        this.interpolationDelayTicks = Math.max(0.0f, interpolationDelayTicks);
    }

    /**
     * Advances the continuous renderTick by deltaSeconds * serverTickRate.
     * Invoked every frame in the render loop.
     *
     * @param deltaSeconds Time elapsed since last render frame (e.g. 1/60f).
     */
    public void update(float deltaSeconds) {
        if (!initialized) {
            Long latestTick = buffer.getLatestServerTick();
            if (latestTick != null) {
                renderTick = Math.max(0.0f, latestTick - interpolationDelayTicks);
                initialized = true;
            }
            return;
        }

        if (deltaSeconds > 0) {
            renderTick += deltaSeconds * serverTickRate;
        }
    }

    /**
     * Calculates the interpolated state of all players in the room for the current renderTick.
     *
     * @return Map of PlayerId -> interpolated PlayerState.
     */
    public Map<PlayerId, PlayerState> getInterpolatedPlayers() {
        if (!initialized || renderTick < 0) {
            return Collections.emptyMap();
        }

        SnapshotBuffer.SnapshotPair pair = buffer.getSurroundingSnapshots(renderTick);
        if (pair == null) {
            return Collections.emptyMap();
        }

        GameSnapshot s0 = pair.from();
        GameSnapshot s1 = pair.to();
        float alpha = pair.alpha();

        if (pair.isIdentical() || alpha <= 0.0f) {
            return toPlayerMap(s0.players());
        }
        if (alpha >= 1.0f) {
            return toPlayerMap(s1.players());
        }

        Map<PlayerId, PlayerState> s1Map = toPlayerMap(s1.players());
        Map<PlayerId, PlayerState> result = new HashMap<>();

        for (PlayerState p0 : s0.players()) {
            PlayerState p1 = s1Map.get(p0.playerId());
            if (p1 != null) {
                result.put(p0.playerId(), interpolatePlayer(p0, p1, alpha));
            } else {
                // Player only existed in S0 (left match / eliminated)
                result.put(p0.playerId(), p0);
            }
        }

        // Include any new player who appeared only in S1
        for (PlayerState p1 : s1.players()) {
            if (!result.containsKey(p1.playerId())) {
                result.put(p1.playerId(), p1);
            }
        }

        return result;
    }

    /**
     * Pure linear interpolation helper between two PlayerState instances.
     *
     * @param p0 Earlier snapshot state.
     * @param p1 Later snapshot state.
     * @param alpha Progress factor in [0.0 .. 1.0].
     * @return Interpolated PlayerState.
     */
    public static PlayerState interpolatePlayer(PlayerState p0, PlayerState p1, float alpha) {
        float dx = p1.x() - p0.x();
        float dy = p1.y() - p0.y();
        float distSq = dx * dx + dy * dy;

        float interpolatedX;
        float interpolatedY;

        if (distSq > TELEPORT_DISTANCE_THRESHOLD * TELEPORT_DISTANCE_THRESHOLD) {
            // Teleport or respawn: snap immediately to destination
            interpolatedX = p1.x();
            interpolatedY = p1.y();
        } else {
            interpolatedX = p0.x() + alpha * dx;
            interpolatedY = p0.y() + alpha * dy;
        }

        float interpolatedVx = p0.velocityX() + alpha * (p1.velocityX() - p0.velocityX());
        float interpolatedVy = p0.velocityY() + alpha * (p1.velocityY() - p0.velocityY());

        // Discrete flags switch at midpoint
        boolean pastMidpoint = alpha >= 0.5f;
        boolean grounded = pastMidpoint ? p1.grounded() : p0.grounded();
        boolean blocking = pastMidpoint ? p1.blocking() : p0.blocking();
        boolean dead = p1.dead() || (pastMidpoint && p0.dead());
        boolean facingRight = pastMidpoint ? p1.facingRight() : p0.facingRight();
        int hp = pastMidpoint ? p1.hp() : p0.hp();
        String characterId = p1.characterId() != null ? p1.characterId() : p0.characterId();

        return new PlayerState(
                p0.playerId(),
                characterId,
                interpolatedX,
                interpolatedY,
                interpolatedVx,
                interpolatedVy,
                hp,
                grounded,
                blocking,
                dead,
                facingRight
        );
    }

    private static Map<PlayerId, PlayerState> toPlayerMap(List<PlayerState> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<PlayerId, PlayerState> map = new HashMap<>();
        for (PlayerState ps : list) {
            map.put(ps.playerId(), ps);
        }
        return map;
    }

    /**
     * Resets internal interpolation state upon match end or scene change.
     */
    public void reset() {
        this.renderTick = -1.0f;
        this.initialized = false;
    }

    public float getRenderTick() {
        return renderTick;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public float getServerTickRate() {
        return serverTickRate;
    }

    public float getInterpolationDelayTicks() {
        return interpolationDelayTicks;
    }
}
