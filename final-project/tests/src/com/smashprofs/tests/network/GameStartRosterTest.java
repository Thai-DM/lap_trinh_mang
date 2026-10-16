package com.smashprofs.tests.network;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.game.network.client.GameStartRoster;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameStartRosterTest {
    private ObjectNode payload(int count) {
        var payload = ProtocolV1.object().put("matchId", "server-match").put("roomId", "arena");
        var players = payload.putArray("players");
        for (int slot = count - 1; slot >= 0; slot--) {
            players.addObject().put("playerId", "server-id-" + slot).put("slotIndex", slot).put("characterId", "Alex");
        }
        return payload;
    }
    @Test public void parsesTwoThreeAndFourPlayersAndMapsLocalByIdentity() {
        for (int count = 2; count <= 4; count++) {
            var roster = GameStartRoster.parse(payload(count));
            assertEquals(count, roster.players().size());
            for (int slot = 0; slot < count; slot++) {
                assertEquals(slot, roster.localPlayer(new PlayerId("server-id-" + slot)).slotIndex());
            }
            assertEquals(0, roster.players().get(0).slotIndex());
            assertThrows(UnsupportedOperationException.class, () -> roster.players().clear());
            assertThrows(IllegalArgumentException.class, () -> roster.localPlayer(new PlayerId("not-in-roster")));
        }
    }
    @Test public void rejectsInvalidSizesAndDuplicateIdentityOrSlot() {
        for (int count : new int[]{0, 1, 5}) {
            assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(payload(count)));
        }
        var duplicateId = payload(2);
        ((ObjectNode) duplicateId.get("players").get(0)).put("playerId", "server-id-0");
        assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(duplicateId));
        var duplicateSlot = payload(2);
        ((ObjectNode) duplicateSlot.get("players").get(0)).put("slotIndex", 0);
        assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(duplicateSlot));
    }
    @Test public void rejectsMalformedSchemaRatherThanStartingAMatch() {
        for (ObjectNode invalid : new ObjectNode[]{
                payload(2).putNull("matchId"), payload(2).put("matchId", ""),
                payload(2).put("roomId", "bad room"), payload(2).put("extra", true)}) {
            assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(invalid));
        }
        for (String field : new String[]{"playerId", "slotIndex", "characterId"}) {
            var invalid = payload(2);
            ((ObjectNode) invalid.get("players").get(0)).putNull(field);
            assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(invalid));
        }
        var invalid = payload(2);
        ((ObjectNode) invalid.get("players").get(0)).put("slotIndex", 0.5);
        assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(invalid));
        ((ObjectNode) invalid.get("players").get(0)).put("slotIndex", 1).put("characterId", "UnknownCharacter");
        assertThrows(IllegalArgumentException.class, () -> GameStartRoster.parse(invalid));
    }
}
