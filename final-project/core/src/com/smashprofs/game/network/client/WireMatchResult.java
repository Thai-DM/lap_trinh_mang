package com.smashprofs.game.network.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.smashprofs.game.network.client.GameStartRoster.*;

/** Immutable GAME_END v1. A null winner is authoritative draw, never inferred from local HP. */
public record WireMatchResult(String matchId, String roomId, long finishedAtTick, String winnerId,
                              String finishReason, List<Participant> participants) {
    public record Participant(String playerId, int slotIndex, String characterId, float finalHp, boolean eliminated) { }
    private static final Set<String> REASONS = Set.of("LAST_PLAYER_STANDING", "ALL_PLAYERS_ELIMINATED", "TIME_LIMIT");
    public WireMatchResult {
        require(matchId != null && !matchId.isBlank() && finishedAtTick >= 0, "Invalid result identity/tick");
        LobbyConfiguration.validateRoomId(roomId);
        require(finishReason != null && REASONS.contains(finishReason), "Invalid finishReason");
        require(participants != null && participants.size() >= 2 && participants.size() <= 4, "Expected 2..4 result participants");
        Set<String> ids = new HashSet<>(); Set<Integer> slots = new HashSet<>();
        for (var p : participants) {
            require(p != null && p.playerId() != null && !p.playerId().isBlank() && ids.add(p.playerId()), "Invalid result playerId");
            require(p.slotIndex() >= 0 && p.slotIndex() <= 3 && slots.add(p.slotIndex()), "Invalid result slot");
            require(p.characterId() != null && CHARACTERS.contains(p.characterId()), "Invalid result character");
            require(Float.isFinite(p.finalHp()) && p.finalHp() >= 0 && p.finalHp() <= 100, "Invalid finalHp");
        }
        require(winnerId == null || ids.contains(winnerId), "Winner missing from result roster");
        participants = participants.stream().sorted(Comparator.comparingInt(Participant::slotIndex)).toList();
    }
    public boolean isDraw() { return winnerId == null; }
    public Participant winner() {
        return isDraw() ? null : participants.stream().filter(p -> p.playerId().equals(winnerId)).findFirst().orElseThrow();
    }
    public void validateRoster(GameStartRoster roster) {
        require(matchId.equals(roster.matchId()) && roomId.equals(roster.roomId()), "GAME_END match/room mismatch");
        require(participants.size() == roster.players().size(), "GAME_END roster size mismatch");
        for (var member : roster.players()) {
            var p = participants.stream().filter(v -> v.playerId().equals(member.playerId().value())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("GAME_END missing roster player"));
            require(p.slotIndex() == member.slotIndex() && p.characterId().equals(member.characterId()), "GAME_END roster mismatch");
        }
    }
    public static WireMatchResult parse(ObjectNode payload) {
        require(payload.size() == 6, "Invalid GAME_END fields");
        var tick = payload.get("finishedAtTick");
        require(tick != null && tick.isIntegralNumber() && tick.canConvertToLong() && tick.longValue() >= 0, "Invalid finishedAtTick");
        var winner = payload.get("winnerId");
        require(winner != null && (winner.isNull() || (winner.isTextual() && !winner.textValue().isBlank())), "Invalid winnerId");
        var array = payload.get("participants"); require(array != null && array.isArray(), "Missing participants");
        List<Participant> participants = new ArrayList<>();
        for (var p : array) {
            require(p.isObject() && p.size() == 5, "Invalid result participant fields");
            var hp = p.get("finalHp"); var eliminated = p.get("eliminated");
            require(hp != null && hp.isNumber() && Double.isFinite(hp.doubleValue()) && Float.isFinite(hp.floatValue()), "Invalid finalHp");
            require(eliminated != null && eliminated.isBoolean(), "Invalid eliminated");
            participants.add(new Participant(text(p, "playerId"), slot(p), text(p, "characterId"), hp.floatValue(), eliminated.booleanValue()));
        }
        return new WireMatchResult(text(payload, "matchId"), text(payload, "roomId"), tick.longValue(),
                winner.isNull() ? null : winner.textValue(), text(payload, "finishReason"), participants);
    }
}
