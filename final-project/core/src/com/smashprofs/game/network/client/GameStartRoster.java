package com.smashprofs.game.network.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import java.util.*;

/** Client value model of the existing v1 GAME_START payload. */
public record GameStartRoster(String matchId, String roomId, List<Participant> players) {
    public static final Set<String> CHARACTERS = Set.of("Alex", "Luca", "Maurice", "Leo", "Viktor");
    public record Participant(PlayerId playerId, int slotIndex, String characterId, String displayName) {
        public Participant(PlayerId playerId, int slotIndex, String characterId) { this(playerId, slotIndex, characterId, null); }
        public Participant { displayName = com.smashprofs.common.model.DisplayName.sanitize(displayName, characterId); }
    }

    public GameStartRoster {
        require(matchId != null && !matchId.isBlank(), "Missing matchId");
        LobbyConfiguration.validateRoomId(roomId);
        require(players != null && players.size() >= 2 && players.size() <= 4, "GAME_START needs 2..4 players");
        Set<PlayerId> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (Participant p : players) {
            require(p != null && p.playerId() != null && p.playerId().value() != null
                    && !p.playerId().value().isBlank(), "Invalid playerId");
            require(p.slotIndex() >= 0 && p.slotIndex() <= 3 && slots.add(p.slotIndex()), "Invalid or duplicate slot");
            require(ids.add(p.playerId()), "Duplicate playerId");
            require(p.characterId() != null && CHARACTERS.contains(p.characterId()), "Invalid characterId");
        }
        players = players.stream().sorted(Comparator.comparingInt(Participant::slotIndex)).toList();
    }

    public Participant localPlayer(PlayerId localId) {
        return players.stream().filter(p -> p.playerId().equals(localId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Local player missing from GAME_START"));
    }

    public static GameStartRoster parse(ObjectNode payload) {
        require(payload.size() == 3, "Invalid GAME_START fields");
        var array = payload.get("players");
        require(array != null && array.isArray(), "Missing GAME_START players");
        List<Participant> players = new ArrayList<>();
        for (var p : array) {
            require(p.isObject() && (p.size() == 3 || p.size() == 4), "Invalid GAME_START player fields");
            players.add(new Participant(new PlayerId(text(p, "playerId")), slot(p), text(p, "characterId"),
                    p.size() == 4 ? text(p, "displayName") : null));
        }
        return new GameStartRoster(text(payload, "matchId"), text(payload, "roomId"), players);
    }

    static String text(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isTextual() && !value.textValue().isBlank(), "Invalid " + field);
        return value.textValue();
    }

    static int slot(JsonNode node) {
        var value = node.get("slotIndex");
        require(value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0 && value.intValue() <= 3, "Invalid slotIndex");
        return value.intValue();
    }

    static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
