package com.smashprofs.tests.network;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.Assert.*;

public class WireGameSnapshotTest {
    static ObjectNode payload(int count, long tick) {
        var root = ProtocolV1.object().put("matchId", "match").put("roomId", "arena")
                .put("serverTick", tick).put("phase", "PLAYING").put("remainingTimeMs", 119950);
        var players = root.putArray("players");
        for (int i = 0; i < count; i++) {
            players.addObject().put("playerId", "opaque-" + i).put("slotIndex", i).put("characterId", "Viktor")
                    .put("x", i + 0.25f).put("y", -1.5f).put("velocityX", 2.75f).put("velocityY", -0.5f)
                    .put("hp", 99.5f).put("grounded", true).put("blocking", false).put("dead", false).put("facingRight", true);
        }
        var projectiles = root.putArray("projectiles");
        String[] types = {"IBM", "STAR", "MINE", "ROCKET"};
        for (int i = 0; i < types.length; i++) {
            projectiles.addObject().put("projectileId", 100L + i).put("matchId", "match")
                    .put("ownerPlayerId", "opaque-0").put("projectileType", types[i]).put("spawnTick", 1)
                    .put("x", 1.25f).put("y", 2.5f).put("velocityX", 3.75f).put("velocityY", -4.5f);
        }
        return root;
    }
    static GameStartRoster roster(int count) {
        List<GameStartRoster.Participant> members = new ArrayList<>();
        for (int i = 0; i < count; i++) { members.add(new GameStartRoster.Participant(new PlayerId("opaque-" + i), i, "Viktor")); }
        return new GameStartRoster("match", "arena", members);
    }

    @Test public void codecAndParserPreserveTwoThreeFourPlayersFractionalHPAndAllProjectileTypes() throws Exception {
        var codec = new JsonFrameCodec();
        for (int count = 2; count <= 4; count++) {
            var envelope = codec.readNext(new ByteArrayInputStream(codec.encode(new ProtocolMessage(MessageType.GAME_STATE, null, payload(count, 3)))));
            var snapshot = WireGameSnapshot.parse(envelope.payload());
            snapshot.validateRoster(roster(count));
            assertEquals(count, snapshot.players().size());
            assertEquals(3, snapshot.serverTick());
            assertEquals(119950, snapshot.remainingTimeMs());
            var last = snapshot.player("opaque-" + (count - 1));
            assertEquals(count - 1, last.slotIndex());
            assertEquals(99.5f, last.hp(), 0);
            assertEquals(-1.5f, last.y(), 0); // world meters, no PPM conversion
            assertTrue(last.grounded()); assertFalse(last.blocking()); assertFalse(last.dead()); assertTrue(last.facingRight());
            assertEquals(List.of("IBM", "STAR", "MINE", "ROCKET"), snapshot.projectiles().stream().map(WireGameSnapshot.Projectile::projectileType).toList());
            var projectile = snapshot.projectiles().get(3);
            assertEquals(103, projectile.projectileId()); assertEquals(1, projectile.spawnTick());
            assertEquals("opaque-0", projectile.ownerPlayerId()); assertEquals("match", projectile.matchId());
            assertEquals(3.75f, projectile.velocityX(), 0); assertEquals(-4.5f, projectile.velocityY(), 0);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.players().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.projectiles().clear());
            ((ObjectNode) envelope.payload().get("players").get(0)).put("hp", 0);
            assertEquals(99.5f, snapshot.player("opaque-0").hp(), 0);
        }
    }

    @Test public void invalidWireValuesAndRosterMismatchAreRejected() {
        List<java.util.function.Consumer<ObjectNode>> mutations = List.of(
                p -> p.put("serverTick", 3.0), p -> p.put("serverTick", -1),
                p -> p.put("remainingTimeMs", 120001), p -> p.put("phase", "WAITING"), p -> p.put("extra", 1),
                p -> ((ObjectNode) p.get("players").get(0)).put("hp", "99.5"),
                p -> ((ObjectNode) p.get("players").get(0)).put("hp", 100.5),
                p -> ((ObjectNode) p.get("players").get(0)).put("x", Double.MAX_VALUE),
                p -> ((ObjectNode) p.get("players").get(0)).put("grounded", 1),
                p -> ((ObjectNode) p.get("players").get(1)).put("playerId", "opaque-0"),
                p -> ((ObjectNode) p.get("players").get(1)).put("slotIndex", 0),
                p -> ((ObjectNode) p.get("projectiles").get(0)).put("matchId", "other"),
                p -> ((ObjectNode) p.get("projectiles").get(0)).put("ownerPlayerId", "outsider"),
                p -> ((ObjectNode) p.get("projectiles").get(0)).put("projectileType", "UNKNOWN"),
                p -> ((ObjectNode) p.get("projectiles").get(1)).put("projectileId", 100));
        for (var mutation : mutations) {
            var p = payload(2, 3); mutation.accept(p);
            assertThrows(IllegalArgumentException.class, () -> WireGameSnapshot.parse(p));
        }
        var wrongMatch = payload(2, 3); wrongMatch.put("matchId", "other"); wrongMatch.putArray("projectiles");
        assertThrows(IllegalArgumentException.class, () -> WireGameSnapshot.parse(wrongMatch).validateRoster(roster(2)));
        var wrongRoom = payload(2, 3); wrongRoom.put("roomId", "other");
        assertThrows(IllegalArgumentException.class, () -> WireGameSnapshot.parse(wrongRoom).validateRoster(roster(2)));
        var wrongCharacter = payload(2, 3); ((ObjectNode) wrongCharacter.get("players").get(1)).put("characterId", "Leo");
        assertThrows(IllegalArgumentException.class, () -> WireGameSnapshot.parse(wrongCharacter).validateRoster(roster(2)));
        assertThrows(IllegalArgumentException.class, () -> WireGameSnapshot.parse(payload(3, 3)).validateRoster(roster(2)));
    }
}
