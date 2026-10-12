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

    /** Production v1 interpolation. Mutable clock state belongs exclusively to the render thread. */
    public static final class V1 {
        public static final double TICK_RATE = 60;
        public static final double DEFAULT_DELAY_TICKS = 6; // 100 ms at 60 Hz, two default broadcasts
        public static final float DEFAULT_TELEPORT_METERS = 1;
        public record Frame(WireGameSnapshot authoritative, double renderTick,
                            List<WireGameSnapshot.Player> players, List<WireGameSnapshot.Projectile> projectiles) {
            public Frame { players = List.copyOf(players); projectiles = List.copyOf(projectiles); }
        }
        private final SnapshotBuffer.V1 buffer;
        private final double delayTicks;
        private final float teleportMeters;
        private final java.util.function.LongSupplier nanoClock;
        private long observedTick = -1, anchorNanos;
        private double renderTick = -1;

        public V1(SnapshotBuffer.V1 buffer) {
            this(buffer, DEFAULT_DELAY_TICKS, DEFAULT_TELEPORT_METERS, System::nanoTime);
        }
        public V1(SnapshotBuffer.V1 buffer, double delayTicks, float teleportMeters,
                  java.util.function.LongSupplier nanoClock) {
            if (buffer == null || nanoClock == null || !Double.isFinite(delayTicks) || delayTicks < 0
                    || !Float.isFinite(teleportMeters) || teleportMeters <= 0) {
                throw new IllegalArgumentException("Invalid v1 interpolation configuration");
            }
            this.buffer = buffer; this.delayTicks = delayTicks; this.teleportMeters = teleportMeters; this.nanoClock = nanoClock;
        }

        /** Clock anchors to an actual server tick on arrival observation, never to frame count/FPS.
         * Clamp at latest on starvation; no extrapolation. HUD/discrete flags use latest authority.
         */
        public Frame sample() {
            var retained = buffer.getSnapshots();
            if (retained.isEmpty()) { return null; }
            var oldest = retained.get(0); var latest = retained.get(retained.size() - 1);
            long now = nanoClock.getAsLong();
            if (observedTick != latest.serverTick()) { observedTick = latest.serverTick(); anchorNanos = now; }
            double desired = observedTick - delayTicks + Math.max(0, now - anchorNanos) / 1_000_000_000.0 * TICK_RATE;
            renderTick = Math.min(latest.serverTick(), Math.max(oldest.serverTick(), Math.max(renderTick, desired)));
            if (latest.phase().equals("FINISHED")) {
                renderTick = latest.serverTick();
                return new Frame(latest, renderTick, latest.players(), latest.projectiles());
            }
            var from = oldest; var to = latest;
            for (var state : retained) {
                if (state.serverTick() <= renderTick) { from = state; }
                if (state.serverTick() >= renderTick) { to = state; break; }
            }
            float alpha = from.serverTick() == to.serverTick() ? 0
                    : (float) ((renderTick - from.serverTick()) / (to.serverTick() - from.serverTick()));
            List<WireGameSnapshot.Player> players = new java.util.ArrayList<>();
            for (var current : latest.players()) {
                var p0 = from.player(current.playerId()); var p1 = to.player(current.playerId());
                var previous = retained.size() > 1 ? retained.get(retained.size() - 2).player(current.playerId()) : current;
                boolean newDiscontinuity = previous.dead() != current.dead()
                        || distanceSquared(previous.x(), previous.y(), current.x(), current.y()) > teleportMeters * teleportMeters;
                boolean snap = p0.dead() != p1.dead() || p0.dead() != current.dead()
                        || newDiscontinuity || distanceSquared(p0.x(), p0.y(), p1.x(), p1.y()) > teleportMeters * teleportMeters;
                var position = newDiscontinuity || p0.dead() != current.dead() ? current : p1;
                float x = snap ? position.x() : lerp(p0.x(), p1.x(), alpha);
                float y = snap ? position.y() : lerp(p0.y(), p1.y(), alpha);
                players.add(new WireGameSnapshot.Player(current.playerId(), current.slotIndex(), current.characterId(), x, y,
                        current.velocityX(), current.velocityY(), current.hp(), current.grounded(), current.blocking(), current.dead(), current.facingRight()));
            }
            // Membership always follows latest authority: confirmed removal cannot linger in delayed rendering.
            Map<Long, WireGameSnapshot.Projectile> fromProjectiles = projectileMap(from), toProjectiles = projectileMap(to);
            List<WireGameSnapshot.Projectile> projectiles = new java.util.ArrayList<>();
            for (var current : latest.projectiles()) {
                var p0 = fromProjectiles.get(current.projectileId()); var p1 = toProjectiles.get(current.projectileId());
                boolean interpolate = p0 != null && p1 != null && p0.spawnTick() == current.spawnTick()
                        && p1.projectileType().equals(current.projectileType())
                        && distanceSquared(p0.x(), p0.y(), p1.x(), p1.y()) <= teleportMeters * teleportMeters;
                projectiles.add(new WireGameSnapshot.Projectile(current.projectileId(), current.matchId(), current.ownerPlayerId(),
                        current.projectileType(), current.spawnTick(), interpolate ? lerp(p0.x(), p1.x(), alpha) : current.x(),
                        interpolate ? lerp(p0.y(), p1.y(), alpha) : current.y(), current.velocityX(), current.velocityY()));
            }
            return new Frame(latest, renderTick, players, projectiles);
        }
        private static Map<Long, WireGameSnapshot.Projectile> projectileMap(WireGameSnapshot state) {
            Map<Long, WireGameSnapshot.Projectile> values = new HashMap<>();
            for (var p : state.projectiles()) { values.put(p.projectileId(), p); }
            return values;
        }
        private static float lerp(float a, float b, float alpha) { return a + alpha * (b - a); }
        private static float distanceSquared(float x0, float y0, float x1, float y1) {
            float dx = x1 - x0, dy = y1 - y0; return dx * dx + dy * dy;
        }
        public double getRenderTick() { return renderTick; }
        public double getDelayTicks() { return delayTicks; }
    }

    // Legacy MockServer tick domain only. Production v1 uses ClientMatchSession's 60 Hz domain;
    // WireGameSnapshot uses the V1 interpolator above, without converting to legacy DTOs.
    public static final float DEFAULT_TICK_RATE = 30.0f;
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
