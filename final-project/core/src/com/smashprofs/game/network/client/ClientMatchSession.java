package com.smashprofs.game.network.client;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Input/snapshot boundary for one validated GAME_START, with no simulation or rendering. */
public final class ClientMatchSession implements AutoCloseable {
    public static final int SIMULATION_HZ = 60;
    public static final int DEFAULT_SNAPSHOT_HZ = 20;
    private final NetworkClient network;
    private final GameStartRoster roster;
    private final PlayerId localId;
    private final OutboundInputQueue inputQueue;
    private final SnapshotBuffer.V1 snapshots = new SnapshotBuffer.V1(32);
    private final NetworkMetricsTracker metrics = new NetworkMetricsTracker(SIMULATION_HZ / DEFAULT_SNAPSHOT_HZ);
    private final LongSupplier nanoClock;
    private final long startedAt;
    private boolean active = true;
    private boolean finished;
    private WireMatchResult result;
    private long lastClientTick;
    private float heldAxis;
    private boolean heldBlock;
    private final java.util.ArrayDeque<com.smashprofs.common.model.PlayerInput> predictionInputs=new java.util.ArrayDeque<>(128);
    public synchronized java.util.List<com.smashprofs.common.model.PlayerInput> getPredictionInputs() { return java.util.List.copyOf(predictionInputs); }
    private final KillFeedModel killFeed=new KillFeedModel();
    public record Ping(boolean connected,Double milliseconds) { }
    private volatile java.util.Map<String,Ping> pings=java.util.Map.of();
    public java.util.Map<String,Ping> getPings() { return pings; }
    public KillFeedModel getKillFeed() { return killFeed; }
    public boolean isSpectating() { var latest=snapshots.getLatestSnapshot();return latest!=null && latest.gameplay()!=null && latest.gameplay().player(localId.value()).lives()==0; }
    public synchronized boolean isFighter() {
        var latest=snapshots.getLatestSnapshot();return latest==null?network.isFighterEnabled():latest.gameplay()!=null && latest.gameplay().rules().fighter();
    }

    ClientMatchSession(NetworkClient network, GameStartRoster roster, PlayerId localId) {
        this(network, roster, localId, System::nanoTime);
    }
    ClientMatchSession(NetworkClient network, GameStartRoster roster, PlayerId localId, LongSupplier nanoClock) {
        this.network = Objects.requireNonNull(network);
        this.roster = Objects.requireNonNull(roster);
        this.localId = Objects.requireNonNull(localId);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        if (network.isMockProtocol() || !localId.equals(network.getPlayerId())) {
            throw new IllegalArgumentException("Match requires a connected production identity");
        }
        roster.localPlayer(localId);
        inputQueue = new OutboundInputQueue(localId);
        startedAt = nanoClock.getAsLong();
        metrics.onConnected();
    }

    /** Render callers sample once per frame. Enqueue/retry uses the existing input queue only. */
    public synchronized boolean recordFrameInput(float axis, boolean jump, boolean stomp,
                                                 boolean melee, boolean block, boolean ability) {
        return recordFrameInput(axis,jump,stomp,melee,block,ability,false,false);
    }
    public synchronized boolean recordFrameInput(float axis, boolean jump, boolean stomp,
                                                 boolean melee, boolean block, boolean ability,
                                                 boolean interact, boolean drop) {
        return recordFrameInput(axis,jump,stomp,melee,block,ability,interact,drop,com.smashprofs.common.model.FighterInput.NONE);
    }
    public synchronized boolean recordFrameInput(float axis,boolean jump,boolean stomp,boolean melee,boolean block,boolean ability,
                                                 boolean interact,boolean drop,com.smashprofs.common.model.FighterInput fighter) {
        if (!Float.isFinite(axis) || axis < -1 || axis > 1) {
            throw new IllegalArgumentException("moveAxis must be finite and in [-1,1]");
        }
        heldAxis=axis;heldBlock=block;
        var latest=snapshots.getLatestSnapshot();
        if(latest!=null && latest.gameplay()!=null && !latest.gameplay().player(localId.value()).status().equals("ACTIVE")) { inputQueue.clear();return false; }
        if (!canSendInput()) { return false; }
        inputQueue.recordFrameInput(axis, jump, stomp, melee, block, ability,
                latest!=null && latest.gameplay()!=null && interact, latest!=null && latest.gameplay()!=null && drop,
                isFighter()?fighter:com.smashprofs.common.model.FighterInput.NONE);
        return flushInput();
    }

    /** Non-blocking retry, also usable without recording another frame. */
    public synchronized boolean flushInput() {
        if (!canSendInput()) { return false; }
        // Metadata: elapsed local match time in a 60 Hz domain, independent of FPS/server authority.
        long elapsed = Math.max(0, nanoClock.getAsLong() - startedAt);
        long tick = (elapsed / 1_000_000_000L) * SIMULATION_HZ
                + (elapsed % 1_000_000_000L) * SIMULATION_HZ / 1_000_000_000L;
        lastClientTick = Math.max(lastClientTick, tick);
        return inputQueue.trySendAccumulatedInput(lastClientTick, input -> {
            var latest=snapshots.getLatestSnapshot();
            var payload=ProtocolV1.inputPayload(input);
            if(network.isFighterEnabled() || isFighter()) { payload.put("matchId",roster.matchId()); }
            boolean sent=network.sendMessage(new ProtocolMessage(MessageType.PLAYER_INPUT,null,payload));
            if(sent && isFighter()) { if(predictionInputs.size()>=128) { predictionInputs.removeFirst(); }predictionInputs.addLast(input); }return sent;
        });
    }

    private boolean canSendInput() {
        if (!active) { return false; }
        if (!localId.equals(network.getPlayerId())) { close(); return false; }
        var latest=snapshots.getLatestSnapshot();
        if(latest!=null && latest.gameplay()!=null && !latest.gameplay().player(localId.value()).status().equals("ACTIVE")) { inputQueue.clear();return false; }
        return !finished;
    }

    /** Reader thread parses immutable values; UI reads the synchronized buffer. */
    synchronized boolean acceptState(ProtocolMessage message) {
        if (!active || !localId.equals(network.getPlayerId())) { close(); return false; }
        if (result != null) { return false; }
        var state = WireGameSnapshot.parse(message.payload());
        state.validateRoster(roster);
        GameStartRoster.require(!finished || state.phase().equals("FINISHED"), "PLAYING snapshot after FINISHED");
        var previous=snapshots.getLatestSnapshot();
        if (!snapshots.push(state)) { return false; }
        if(state.gameplay()!=null && state.gameplay().fighter()!=null) {
            long ack=state.gameplay().fighter().player(localId.value()).acknowledgedSequence();
            predictionInputs.removeIf(input->input.sequence()<=ack);
            if(!state.gameplay().player(localId.value()).status().equals("ACTIVE")) { predictionInputs.clear(); }
        }
        if(state.gameplay()!=null && !state.gameplay().player(localId.value()).status().equals("ACTIVE")) { inputQueue.clear(); }
        metrics.onSnapshotReceived(state.serverTick());
        if (state.phase().equals("FINISHED")) { finished = true; inputQueue.clear();predictionInputs.clear(); }
        else if(previous!=null && previous.gameplay()!=null && state.gameplay()!=null
                && previous.gameplay().player(localId.value()).status().equals("RESPAWNING")
                && state.gameplay().player(localId.value()).status().equals("ACTIVE")) {
            // Resume held movement on the reader's first active snapshot, without replaying dead-time attacks.
            inputQueue.recordFrameInput(heldAxis,false,false,false,heldBlock,false);
            flushInput();
        }
        return true;
    }

    /** Accept once, after the ordered final GAME_STATE; no ranking wait or UI callback. */
    synchronized boolean acceptEnd(ProtocolMessage message) {
        if (!active || !localId.equals(network.getPlayerId())) { return false; }
        var incoming = WireMatchResult.parse(message.payload());
        incoming.validateRoster(roster);
        if (result != null) {
            GameStartRoster.require(result.equals(incoming), "Conflicting duplicate GAME_END");
            return false;
        }
        var last = snapshots.getLatestSnapshot();
        GameStartRoster.require(last != null && last.phase().equals("FINISHED")
                && last.serverTick() == incoming.finishedAtTick(), "GAME_END missing matching final snapshot");
        for (var p : incoming.participants()) {
            var state = last.player(p.playerId());
            boolean eliminated=last.gameplay()==null?state.dead():last.gameplay().player(p.playerId()).lives()==0;
            GameStartRoster.require(Float.compare(p.finalHp(), state.hp()) == 0 && p.eliminated() == eliminated,
                    "GAME_END final player state mismatch");
        }
        GameStartRoster.require(Objects.equals(incoming.gameplay(),last.gameplay()),"Result statistics differ from final state");
        finished = true;
        inputQueue.clear();
        predictionInputs.clear();
        result = incoming;
        return true;
    }
    synchronized void acceptEvent(ProtocolMessage message) {
        var p=message.payload();
        GameStartRoster.require(roster.matchId().equals(p.path("matchId").asText()) && roster.roomId().equals(p.path("roomId").asText()),"Combat event ownership");
        String killer=p.path("killer").isNull()?null:p.path("killer").asText();String victim=p.path("victim").isNull()?null:p.path("victim").asText();
        var ids=roster.players().stream().map(v->v.playerId().value()).toList();
        GameStartRoster.require((killer==null || ids.contains(killer)) && (victim==null || ids.contains(victim)),"Combat event identity");
        killFeed.accept(new com.smashprofs.common.model.MatchEvent(p.path("sequence").asLong(-1),p.path("serverTick").asLong(-1),p.path("type").asText(),killer,victim,p.path("weapon").asText(),p.path("cause").asText()));
    }

    public GameStartRoster getRoster() { return roster; }
    synchronized void acceptRtt(ProtocolMessage message) {
        var p=message.payload();
        GameStartRoster.require(roster.matchId().equals(p.path("matchId").asText()) && roster.roomId().equals(p.path("roomId").asText())
                && p.path("players").isArray() && p.path("players").size()==roster.players().size(),"RTT ownership/roster");
        var result=new java.util.HashMap<String,Ping>();
        for(var player:p.path("players")) {
            String id=player.path("playerId").asText();roster.localPlayer(new PlayerId(id));
            var value=player.path("rttMs");
            GameStartRoster.require(value.isNull() || value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble()>=0,"Invalid RTT");
            GameStartRoster.require(player.path("connected").isBoolean() && !result.containsKey(id),"Invalid RTT player");
            result.put(id,new Ping(player.path("connected").asBoolean(),value.isNull()?null:value.asDouble()));
        }
        pings=java.util.Map.copyOf(result);
    }
    public PlayerId getLocalPlayerId() { return localId; }
    public SnapshotBuffer.V1 getSnapshotBuffer() { return snapshots; }
    public NetworkMetricsTracker getMetrics() { return metrics; }
    public synchronized int getPendingInputCount() { return inputQueue.size(); }
    public synchronized boolean isActive() { return canSendInput(); }
    public synchronized WireMatchResult getResult() { return result; }
    @Override public synchronized void close() {
        active = false;
        heldAxis=0;heldBlock=false;
        predictionInputs.clear();
        inputQueue.clear();
        snapshots.clear();
        killFeed.clear();
        pings=java.util.Map.of();
        metrics.onDisconnected("Match session closed");
    }
}
