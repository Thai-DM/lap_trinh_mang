package com.smashprofs.game.network.client;

import java.util.*;

/** Render-thread value mapping; never constructs offline Player/World/combat or owns the TCP client. */
public final class OnlineMatchPresentation implements AutoCloseable {
    public record PlayerView(WireGameSnapshot.Player state, boolean local) { }
    private final ClientMatchSession session;
    private final StateInterpolator.V1 interpolator;
    private StateInterpolator.V1.Frame frame;
    private List<PlayerView> players = List.of();
    private Map<Long, WireGameSnapshot.Projectile> projectiles = Map.of();
    private boolean resultConsumed, closed;

    public OnlineMatchPresentation(ClientMatchSession session) {
        this(session, new StateInterpolator.V1(session.getSnapshotBuffer()));
    }
    public OnlineMatchPresentation(ClientMatchSession session, StateInterpolator.V1 interpolator) {
        this.session = Objects.requireNonNull(session); this.interpolator = Objects.requireNonNull(interpolator);
    }
    public void update() {
        if (closed) { return; }
        var next = interpolator.sample();
        if (next == null) { return; } // Freeze last received display on disconnect, alongside explicit connection status.
        next.authoritative().validateRoster(session.getRoster());
        frame = next;
        players = next.players().stream().map(p -> new PlayerView(p, p.playerId().equals(session.getLocalPlayerId().value()))).toList();
        Map<Long, WireGameSnapshot.Projectile> byId = new LinkedHashMap<>();
        for (var projectile : next.projectiles()) { byId.put(projectile.projectileId(), projectile); }
        projectiles = Collections.unmodifiableMap(byId);
    }
    public List<PlayerView> getPlayers() { return players; }
    public Map<Long, WireGameSnapshot.Projectile> getProjectiles() { return projectiles; }
    public StateInterpolator.V1.Frame getFrame() { return frame; }
    /** Screen consumes once on the render thread. Duplicate network pushes cannot create a second transition. */
    public WireMatchResult takeResultTransition() {
        if (closed || resultConsumed) { return null; }
        var result = session.getResult();
        if (result == null) { return null; }
        result.validateRoster(session.getRoster());
        resultConsumed = true;
        return result;
    }
    @Override public void close() { closed = true; frame = null; players = List.of(); projectiles = Map.of(); }
}
