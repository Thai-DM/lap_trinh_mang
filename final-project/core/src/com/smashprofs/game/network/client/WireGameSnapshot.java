package com.smashprofs.game.network.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.smashprofs.game.network.client.GameStartRoster.*;

/** Immutable TCP v1 values in world meters. No legacy DTO, libGDX, or physics objects. */
public record WireGameSnapshot(String matchId, String roomId, long serverTick, String phase,
                               long remainingTimeMs, List<Player> players, List<Projectile> projectiles,
                               com.smashprofs.common.model.GameplaySnapshot gameplay) {
    public WireGameSnapshot(String matchId,String roomId,long serverTick,String phase,long remainingTimeMs,List<Player> players,List<Projectile> projectiles) {
        this(matchId,roomId,serverTick,phase,remainingTimeMs,players,projectiles,null);
    }
    public record Player(String playerId, int slotIndex, String characterId, float x, float y,
                         float velocityX, float velocityY, float hp, boolean grounded,
                         boolean blocking, boolean dead, boolean facingRight) { }
    public record Projectile(long projectileId, String matchId, String ownerPlayerId, String projectileType,
                             long spawnTick, float x, float y, float velocityX, float velocityY) { }
    private static final Set<String> PROJECTILE_TYPES = Set.of("IBM", "STAR", "MINE", "ROCKET");

    public WireGameSnapshot {
        require(matchId != null && !matchId.isBlank(), "Invalid matchId");
        LobbyConfiguration.validateRoomId(roomId);
        require(serverTick >= 0 && remainingTimeMs >= 0 && remainingTimeMs <= (gameplay==null?120000:300000), "Invalid snapshot clock");
        require(phase != null && Set.of("PLAYING", "FINISHED").contains(phase), "Invalid match phase");
        require(players != null && players.size() >= 2 && players.size() <= 4, "Expected 2..4 players");
        Set<String> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (Player p : players) {
            require(p != null && p.playerId() != null && !p.playerId().isBlank() && ids.add(p.playerId()), "Invalid player identity");
            require(p.slotIndex() >= 0 && p.slotIndex() <= 3 && slots.add(p.slotIndex()), "Invalid player slot");
            require(p.characterId() != null && CHARACTERS.contains(p.characterId()), "Invalid characterId");
            finite(p.x(), p.y(), p.velocityX(), p.velocityY(), p.hp());
            require(p.hp() >= 0 && p.hp() <= 100, "Invalid HP");
        }
        require(projectiles != null, "Missing projectiles");
        Set<Long> projectileIds = new HashSet<>();
        for (Projectile p : projectiles) {
            require(p != null && p.projectileId() >= 0 && projectileIds.add(p.projectileId()), "Invalid projectileId");
            require(matchId.equals(p.matchId()) && ids.contains(p.ownerPlayerId()), "Invalid projectile identity");
            require(p.projectileType() != null && PROJECTILE_TYPES.contains(p.projectileType()) && p.spawnTick() >= 0, "Invalid projectile state");
            finite(p.x(), p.y(), p.velocityX(), p.velocityY());
        }
        players = players.stream().sorted(Comparator.comparingInt(Player::slotIndex)).toList();
        projectiles = projectiles.stream().sorted(Comparator.comparingLong(Projectile::projectileId)).toList();
        if(gameplay!=null) {
            require(gameplay.players().size()==players.size(),"Gameplay roster size");
            for(var p:players) { var progress=gameplay.player(p.playerId());require(p.dead()!=progress.status().equals("ACTIVE"),"Gameplay life state mismatch"); }
        }
    }

    public Player player(String playerId) {
        return players.stream().filter(p -> p.playerId().equals(playerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Player missing from snapshot"));
    }

    /** GAME_START is the identity/slot/character authority for every snapshot on this connection. */
    public void validateRoster(GameStartRoster roster) {
        require(matchId.equals(roster.matchId()) && roomId.equals(roster.roomId()), "Snapshot match/room mismatch");
        require(players.size() == roster.players().size(), "Snapshot roster size mismatch");
        for (var member : roster.players()) {
            var p = player(member.playerId().value());
            require(p.slotIndex() == member.slotIndex() && p.characterId().equals(member.characterId()), "Snapshot roster mismatch");
        }
    }

    public static WireGameSnapshot parse(ObjectNode payload) {
        require(payload.size() == (payload.has("gameplay")?8:7), "Invalid GAME_STATE fields");
        var playerArray = payload.get("players");
        var projectileArray = payload.get("projectiles");
        require(playerArray != null && playerArray.isArray() && projectileArray != null && projectileArray.isArray(), "Invalid snapshot arrays");
        List<Player> players = new ArrayList<>();
        for (var p : playerArray) {
            require(p.isObject() && p.size() == 12, "Invalid player fields");
            players.add(new Player(text(p, "playerId"), slot(p), text(p, "characterId"), number(p, "x"), number(p, "y"),
                    number(p, "velocityX"), number(p, "velocityY"), number(p, "hp"), bool(p, "grounded"),
                    bool(p, "blocking"), bool(p, "dead"), bool(p, "facingRight")));
        }
        List<Projectile> projectiles = new ArrayList<>();
        for (var p : projectileArray) {
            require(p.isObject() && p.size() == 9, "Invalid projectile fields");
            projectiles.add(new Projectile(integer(p, "projectileId"), text(p, "matchId"), text(p, "ownerPlayerId"),
                    text(p, "projectileType"), integer(p, "spawnTick"), number(p, "x"), number(p, "y"),
                    number(p, "velocityX"), number(p, "velocityY")));
        }
        return new WireGameSnapshot(text(payload, "matchId"), text(payload, "roomId"), integer(payload, "serverTick"),
                text(payload, "phase"), integer(payload, "remainingTimeMs"), players, projectiles,
                payload.has("gameplay")?com.smashprofs.common.protocol.GameplayProtocol.snapshot(payload.get("gameplay")):null);
    }

    private static float number(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()) && Float.isFinite(value.floatValue()), "Invalid " + field);
        return value.floatValue();
    }
    private static long integer(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0, "Invalid " + field);
        return value.longValue();
    }
    private static boolean bool(JsonNode node, String field) {
        var value = node.get(field);
        require(value != null && value.isBoolean(), "Invalid " + field);
        return value.booleanValue();
    }
    private static void finite(float... values) {
        for (float value : values) { require(Float.isFinite(value), "Non-finite state"); }
    }
}
