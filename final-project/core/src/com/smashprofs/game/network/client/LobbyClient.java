package com.smashprofs.game.network.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static com.smashprofs.game.network.client.GameStartRoster.*;

/** Owns one lobby connection. UI polls immutable snapshots; this class never calls libGDX. */
public final class LobbyClient implements ConnectionListener, AutoCloseable {
    public enum Phase { IDLE, CONNECTING, CREATING, JOINING, JOINED, READY_PENDING, WAITING_FOR_START, STARTED, ERROR, DISCONNECTED }
    public record Member(PlayerId playerId, int slotIndex, String characterId, boolean ready) { }
    public record Snapshot(Phase phase, PlayerId localPlayerId, String roomId, String serverPhase,
                           List<Member> members, String selectedCharacter, GameStartRoster match,
                           String errorCode, String statusText) {
        public Snapshot { members = List.copyOf(members); }
        public boolean isMatchStarted() { return phase == Phase.STARTED && match != null; }
        public boolean canReady() { return phase == Phase.JOINED; }
    }
    private record Pending(MessageType type, String requestId, String characterId) { }
    private static final Logger log = LogManager.getLogger(LobbyClient.class);
    private static final Set<String> ROOM_PHASES = Set.of("WAITING", "READY", "COUNTDOWN", "PLAYING", "FINISHED");
    private final LobbyConfiguration configuration;
    private final NetworkClient networkClient;
    private final Consumer<Snapshot> updates;
    private volatile Snapshot snapshot;
    private volatile boolean closed;
    private Thread connector, closer;
    // Mutation is serialized by this object's monitor; only the immutable snapshot crosses threads.
    private Phase phase = Phase.IDLE;
    private PlayerId localId;
    private List<Member> members = List.of();
    private String selectedCharacter, serverPhase = "", errorCode, statusText = "Not connected";
    private GameStartRoster match;
    private volatile ClientMatchSession matchSession;
    private Pending pending;
    private boolean gameplay;
    public record Settings(String hostId,com.smashprofs.common.model.MatchRules rules,int playerCount,long version,long rulesVersion,List<Member> members) {
        public Settings { members=List.copyOf(members); }
        public Settings(String hostId,com.smashprofs.common.model.MatchRules rules,int playerCount,long version,long rulesVersion) {
            this(hostId,rules,playerCount,version,rulesVersion,List.of());
        }
    }
    private long confirmedReadyRulesVersion=-1;
    private volatile Settings settings;
    public synchronized void enableGameplay() { if(phase!=Phase.IDLE) { throw new IllegalStateException("Negotiate before start"); }gameplay=true;networkClient.enableGameplay(); }
    public boolean supportsGameplay() { return gameplay; }
    public Settings getSettings() { return settings; }
    public synchronized boolean updateSettings(com.smashprofs.common.model.MatchRules rules) {
        if(!gameplay || phase!=Phase.JOINED || pending!=null || settings==null || !settings.hostId().equals(localId.value())) { return false; }
        var payload=ProtocolV1.object().put("roomId",configuration.roomId());payload.set("rules",GameplayProtocol.rules(rules));
        return request(MessageType.ROOM_SETTINGS_UPDATE,payload,null);
    }

    public LobbyClient(LobbyConfiguration configuration) { this(configuration, s -> { }); }
    /** State observers may run on connector/network/caller threads; UI should poll getSnapshot instead. */
    public LobbyClient(LobbyConfiguration configuration, Consumer<Snapshot> updates) {
        this(configuration, new NetworkClient(configuration.host(), configuration.port(), null), updates);
    }
    /** Existing NetworkClient may be injected for integration tests; the lobby owns its listener/lifecycle. */
    public LobbyClient(LobbyConfiguration configuration, NetworkClient networkClient, Consumer<Snapshot> updates) {
        this.configuration = Objects.requireNonNull(configuration);
        this.networkClient = Objects.requireNonNull(networkClient);
        this.updates = Objects.requireNonNull(updates);
        networkClient.setListener(this);
        snapshot = buildSnapshot();
    }

    public Snapshot getSnapshot() { return snapshot; }
    public NetworkClient getNetworkClient() { return networkClient; }
    public ClientMatchSession getMatchSession() { return matchSession; }

    /** Minimal online input boundary; offline/mock PlayScreen never calls this path. */
    public synchronized boolean recordFrameInput(float axis, boolean jump, boolean stomp,
                                                 boolean melee, boolean block, boolean ability) {
        return !closed && phase == Phase.STARTED && matchSession != null
                && matchSession.recordFrameInput(axis, jump, stomp, melee, block, ability);
    }

    /** One-shot background TCP connect. Repeated UI/callback calls do not restart the flow. */
    public synchronized boolean startAsync() {
        if (closed || phase != Phase.IDLE) { return false; }
        phase = Phase.CONNECTING;
        publish("Connecting to " + configuration.host() + ":" + configuration.port());
        connector = new Thread(() -> {
            try { networkClient.connect(); }
            catch (IOException | RuntimeException failure) {
                synchronized (this) {
                    if (!closed) { fail("CONNECTION_FAILED", failure.getMessage(), false); }
                }
            }
            finally { if (closed) { networkClient.disconnect(); } }
        }, "LobbyClient-Connect");
        connector.setDaemon(true);
        connector.start();
        return true;
    }

    @Override public synchronized void onConnected() {
        if (closed || phase != Phase.CONNECTING) { return; }
        localId = networkClient.getPlayerId();
        if (localId == null) { fail("INVALID_SESSION", "Missing server-issued player identity", false); return; }
        if (configuration.mode() == LobbyConfiguration.Mode.CREATE) {
            var payload=ProtocolV1.object().put("roomId",configuration.roomId());if(gameplay) { payload.put("gameplay",true); }
            request(MessageType.CREATE_ROOM, payload, null);
        } else { join(); }
    }

    private void join() {
        request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", configuration.roomId()), null);
    }

    /** Called explicitly by the local character selection UI, once per accepted/in-flight READY. */
    public synchronized boolean ready(String characterId) {
        if (closed || phase != Phase.JOINED) { return false; }
        if (characterId == null || !CHARACTERS.contains(characterId)) {
            fail("INVALID_CHARACTER", "Choose a valid character", true);
            return false;
        }
        return request(MessageType.PLAYER_READY, ProtocolV1.object().put("roomId", configuration.roomId())
                .put("characterId", characterId).put("ready", true), characterId);
    }

    /** Gameplay lobby can cancel readiness before the server starts the match. */
    public synchronized boolean unready() {
        if(!gameplay || closed || phase!=Phase.WAITING_FOR_START || pending!=null || selectedCharacter==null) { return false; }
        return request(MessageType.PLAYER_READY,ProtocolV1.object().put("roomId",configuration.roomId())
                .put("characterId",selectedCharacter).put("ready",false),selectedCharacter);
    }

    private boolean request(MessageType type, ObjectNode payload, String characterId) {
        Pending operation = new Pending(type, UUID.randomUUID().toString(), characterId);
        pending = operation;
        phase = switch (type) {
            case CREATE_ROOM -> Phase.CREATING;
            case JOIN_ROOM -> Phase.JOINING;
            case PLAYER_READY -> Phase.READY_PENDING;
            case ROOM_SETTINGS_UPDATE -> Phase.READY_PENDING;
            default -> throw new IllegalArgumentException("Not a lobby request");
        };
        errorCode = null;
        publish(switch (type) {
            case CREATE_ROOM -> "Creating room " + configuration.roomId();
            case JOIN_ROOM -> "Joining room " + configuration.roomId();
            case ROOM_SETTINGS_UPDATE -> "Updating room rules";
            default -> "Sending ready: " + characterId;
        });
        if (closed) { return false; }
        if (!networkClient.sendMessage(new ProtocolMessage(type, operation.requestId(), payload))) {
            pending = null;
            fail("SEND_FAILED", "Unable to queue lobby request", type == MessageType.PLAYER_READY);
            return false;
        }
        return true;
    }

    @Override public void onPacketReceived(NetworkPacket packet) { /* Full v1 callback below owns lobby routing. */ }

    @Override public synchronized void onProtocolMessageReceived(ProtocolMessage message) {
        if (closed || phase == Phase.DISCONNECTED || phase == Phase.ERROR) { return; }
        try {
            require(message.version() == 1, "Invalid envelope version");
            switch (message.type()) {
                case ROOM_CREATED -> {
                    if (!correlates(message, MessageType.CREATE_ROOM)) { return; }
                    require(message.payload().size() == 1, "Invalid ROOM_CREATED fields");
                    require(configuration.roomId().equals(text(message.payload(), "roomId")), "ROOM_CREATED room mismatch");
                    pending = null;
                    join(); // Creating a room does not imply membership.
                }
                case ROOM_STATE -> acceptRoomState(message);
                case ROOM_SETTINGS_STATE -> acceptSettings(message);
                case MATCH_EVENT -> { if(matchSession!=null) { matchSession.acceptEvent(message); } }
                case RTT_STATE -> { if(matchSession!=null) { matchSession.acceptRtt(message); } }
                case GAME_START -> acceptStart(message);
                case GAME_STATE -> {
                    if (phase == Phase.STARTED && matchSession != null) { matchSession.acceptState(message); }
                }
                case GAME_END -> {
                    if (phase == Phase.STARTED && matchSession != null && matchSession.acceptEnd(message)) {
                        serverPhase = "FINISHED";
                        publish("Match finished: " + matchSession.getResult().finishReason());
                    }
                }
                case ERROR -> acceptError(message);
                default -> { /* CONNECT/heartbeat belong to NetworkClient. */ }
            }
        } catch (IllegalArgumentException invalid) {
            clearMatchSession();
            pending = null;
            fail("INVALID_SERVER_MESSAGE", invalid.getMessage(), false);
        }
    }

    private boolean correlates(ProtocolMessage message, MessageType type) {
        return pending != null && pending.type() == type && pending.requestId().equals(message.requestId());
    }

    private void acceptRoomState(ProtocolMessage message) {
        if (!correlates(message, MessageType.JOIN_ROOM) && !correlates(message, MessageType.PLAYER_READY)) { return; }
        var payload = message.payload();
        require(payload.size() == (payload.has("settings")?4:3) && configuration.roomId().equals(text(payload, "roomId")), "ROOM_STATE room mismatch");
        String roomPhase = text(payload, "phase");
        require(ROOM_PHASES.contains(roomPhase), "Invalid room phase");
        var array = payload.get("players");
        require(array != null && array.isArray() && array.size() <= 4, "Invalid lobby roster");
        List<Member> roster = new ArrayList<>();
        Set<PlayerId> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (var p : array) {
            require(p.isObject() && p.size() == 4, "Invalid lobby member fields");
            var id = new PlayerId(text(p, "playerId"));
            int index = slot(p);
            require(ids.add(id) && slots.add(index), "Duplicate lobby player or slot");
            var character = p.get("characterId");
            require(character != null && (character.isNull() || character.isTextual()), "Invalid characterId");
            String name = character.isNull() ? null : character.textValue();
            require(name == null || CHARACTERS.contains(name), "Invalid characterId");
            var ready = p.get("ready");
            require(ready != null && ready.isBoolean() && (!ready.booleanValue() || name != null), "Invalid ready flag");
            roster.add(new Member(id, index, name, ready.booleanValue()));
        }
        var local = roster.stream().filter(p -> p.playerId().equals(localId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Local player missing from ROOM_STATE"));
        boolean readyResponse = pending.type() == MessageType.PLAYER_READY;
        Settings acknowledgedRules=payload.has("settings")?readSettings(payload.get("settings")):null;
        if(acknowledgedRules!=null) {
            if(settings==null || acknowledgedRules.version()>=settings.version()) { settings=acknowledgedRules; }
            if(readyResponse) { confirmedReadyRulesVersion=acknowledgedRules.rulesVersion(); }
        }
        if (readyResponse) {
            require((local.ready() || acknowledgedRules!=null) && pending.characterId().equals(local.characterId()), "READY confirmation mismatch");
        }
        members = roster.stream().sorted(Comparator.comparingInt(Member::slotIndex)).toList();
        serverPhase = roomPhase;
        selectedCharacter = local.characterId();
        pending = null;
        errorCode = null;
        phase = readyResponse && local.ready() ? Phase.WAITING_FOR_START : Phase.JOINED;
        if(readyResponse && settings!=null && confirmedReadyRulesVersion<settings.rulesVersion()) { phase=Phase.JOINED; }
        publish(readyResponse ? (local.ready()?"Ready. Waiting for server to start the match":"Not ready. You can change character or room rules.")
                : "Joined " + configuration.roomId() + ". Choose your character, then press Ready");
    }
    private void acceptSettings(ProtocolMessage message) {
        var incoming=readSettings(message.payload());boolean response=correlates(message,MessageType.ROOM_SETTINGS_UPDATE);
        if(settings!=null && incoming.version()<settings.version()) {
            if(response) { pending=null;phase=Phase.JOINED;publish("Room rules updated. Ready again."); }return;
        }
        settings=incoming;
        if(!incoming.members().isEmpty()) { members=incoming.members(); }
        if(response || (phase==Phase.WAITING_FOR_START && incoming.rulesVersion()>confirmedReadyRulesVersion)) {
            pending=null;phase=Phase.JOINED;publish("Room rules updated. Ready again.");
        } else { publish(statusText); }
    }
    private Settings readSettings(com.fasterxml.jackson.databind.JsonNode p) {
        require(gameplay && configuration.roomId().equals(text(p,"roomId")),"Unnegotiated settings/room mismatch");
        var rules=GameplayProtocol.rules(p.get("rules"));
        require(p.path("version").isIntegralNumber() && p.path("rulesVersion").isIntegralNumber()
                && p.path("version").asLong()>=1 && p.path("rulesVersion").asLong()>=1
                && p.path("rulesVersion").asLong()<=p.path("version").asLong() && p.path("playerCount").isIntegralNumber()
                && p.path("playerCount").asInt()>=1 && p.path("playerCount").asInt()<=rules.maxPlayers(),"Invalid settings revision/count");
        var roster=new ArrayList<Member>();
        if(p.has("players")) {
            var array=p.get("players");require(array.isArray() && array.size()==p.path("playerCount").asInt(),"Invalid settings roster");
            var ids=new HashSet<PlayerId>();var slots=new HashSet<Integer>();
            for(var member:array) {
                require(member.isObject() && member.size()==4,"Invalid settings member");
                var id=new PlayerId(text(member,"playerId"));int index=slot(member);
                require(index<rules.maxPlayers() && ids.add(id) && slots.add(index),"Duplicate/out-of-capacity settings member");
                var character=member.get("characterId");var ready=member.get("ready");
                require(character!=null && (character.isNull() || character.isTextual() && CHARACTERS.contains(character.textValue())),"Invalid settings character");
                require(ready!=null && ready.isBoolean() && (!ready.booleanValue() || character.isTextual()),"Invalid settings ready");
                roster.add(new Member(id,index,character.isNull()?null:character.textValue(),ready.booleanValue()));
            }
            require(localId==null || ids.contains(localId),"Local player missing from settings");
            roster.sort(Comparator.comparingInt(Member::slotIndex));
        }
        return new Settings(text(p,"hostId"),rules,p.path("playerCount").asInt(),p.path("version").asLong(),p.path("rulesVersion").asLong(),roster);
    }

    private void acceptStart(ProtocolMessage message) {
        if (phase != Phase.READY_PENDING && phase != Phase.WAITING_FOR_START && phase != Phase.STARTED) { return; }
        var start = GameStartRoster.parse(message.payload());
        require(configuration.roomId().equals(start.roomId()), "GAME_START room mismatch");
        var local = start.localPlayer(localId);
        var joined = members.stream().filter(p -> p.playerId().equals(localId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Local player missing from confirmed lobby"));
        require(local.slotIndex() == joined.slotIndex(), "GAME_START local slot mismatch");
        String chosen = pending != null && pending.type() == MessageType.PLAYER_READY ? pending.characterId() : selectedCharacter;
        require(local.characterId().equals(chosen), "GAME_START local character mismatch");
        if (match != null) {
            require(match.equals(start), "Unexpected second match on this connection");
            return; // Idempotent duplicate GAME_START, never a second screen transition.
        }
        match = start;
        matchSession = new ClientMatchSession(networkClient, start, localId);
        members = start.players().stream().map(p -> new Member(p.playerId(), p.slotIndex(), p.characterId(), true)).toList();
        selectedCharacter = local.characterId();
        pending = null;
        phase = Phase.STARTED;
        serverPhase = "PLAYING";
        errorCode = null;
        publish("Match started. Local slot " + local.slotIndex() + " / " + start.players().size() + " players");
    }

    private void acceptError(ProtocolMessage message) {
        var payload = message.payload();
        require(payload.size() == 2, "Invalid ERROR fields");
        String code = text(payload, "code"), description = text(payload, "message");
        if (pending != null && pending.requestId().equals(message.requestId())) {
            boolean readyRejected = pending.type() == MessageType.PLAYER_READY || pending.type()==MessageType.ROOM_SETTINGS_UPDATE;
            pending = null;
            fail(code, description, readyRejected);
        } else if (phase == Phase.CONNECTING) {
            fail(code, description, false);
        } else {
            errorCode = code;
            publish("Server error [" + code + "]: " + description);
        } // Unrelated errors remain visible without changing accepted lobby/match state or retrying.
    }

    private void fail(String code, String message, boolean retainJoined) {
        phase = retainJoined ? Phase.JOINED : Phase.ERROR;
        errorCode = code;
        publish("Error [" + code + "]: " + message);
    }

    @Override public synchronized void onDisconnected(String reason) {
        clearMatchSession();
        if (closed) { return; }
        pending = null;
        phase = Phase.DISCONNECTED;
        clearConnectionState();
        publish("Disconnected: " + reason);
    }

    private Snapshot buildSnapshot() {
        return new Snapshot(phase, localId, configuration.roomId(), serverPhase, members,
                selectedCharacter, match, errorCode, statusText);
    }

    private void publish(String text) {
        statusText = text;
        snapshot = buildSnapshot();
        try { updates.accept(snapshot); }
        catch (RuntimeException failure) { log.error("Lobby state observer failed", failure); }
    }

    /** Non-blocking shutdown for render/UI callers, including a still-pending TCP connect. */
    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        clearMatchSession();
        pending = null;
        phase = Phase.DISCONNECTED;
        clearConnectionState();
        publish("Disconnected");
        closer = new Thread(() -> networkClient.disconnectAndWait(3000), "LobbyClient-Close");
        closer.setDaemon(true);
        closer.start();
    }

    private void clearMatchSession() {
        if (matchSession != null) { matchSession.close(); matchSession = null; }
    }

    private void clearConnectionState() {
        localId = null;
        match = null;
        members = List.of();
        selectedCharacter = null;
        serverPhase = "";
        errorCode = null;
    }

    /** Bounded cleanup for tests/non-render callers. */
    public void closeAndWait(long timeoutMillis) throws InterruptedException {
        close();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Thread worker : new Thread[]{connector, closer}) {
            if (worker != null && worker != Thread.currentThread()) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) { worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
            }
        }
    }
}
