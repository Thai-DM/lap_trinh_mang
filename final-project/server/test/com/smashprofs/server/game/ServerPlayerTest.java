package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class ServerPlayerTest {

    @Test
    public void playerStartsWithCorrectMetadataAndInitialState() {
        PlayerId playerId = new PlayerId("player-1");
        ServerPlayer player = new ServerPlayer(playerId, "Luca", 2);

        assertEquals(playerId, player.getPlayerId());
        assertEquals("Luca", player.getCharacterId());
        assertEquals(2, player.getSlotIndex());
        assertEquals(100.0f, player.getHp(), 0.0f);
        assertFalse(player.isDead());
    }

    @Test
    public void allBaselineCharactersAreAccepted() {
        for (String characterId : List.of("Alex", "Luca", "Maurice", "Leo", "Viktor")) {
            ServerPlayer player = new ServerPlayer(new PlayerId("player-1"), characterId, 0);

            assertEquals(characterId, player.getCharacterId());
        }
    }

    @Test
    public void slotZeroAndThreeAreValid() {
        for (int index : new int[]{0, 3}) {
            assertEquals(index, new ServerPlayer(new PlayerId("player-1"), "Alex", index).getSlotIndex());
        }
    }

    @Test
    public void invalidSlotIsRejected() {
        for (int index : new int[]{-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServerPlayer(new PlayerId("player-1"), "Alex", index));
        }
    }

    @Test
    public void nullPlayerIdAndValueAreRejected() {
        assertThrows(NullPointerException.class, () -> new ServerPlayer(null, "Alex", 0));
        assertThrows(NullPointerException.class, () -> new ServerPlayer(new PlayerId(null), "Alex", 0));
    }

    @Test
    public void blankPlayerIdIsRejected() {
        for (String blank : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServerPlayer(new PlayerId(blank), "Alex", 0));
        }
    }

    @Test
    public void nullCharacterIsRejected() {
        assertThrows(NullPointerException.class, () -> new ServerPlayer(new PlayerId("player-1"), null, 0));
    }

    @Test
    public void invalidCharacterIsRejected() {
        for (String invalid : List.of("", " ", "Unknown", "alex", " Alex ", "Momo")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ServerPlayer(new PlayerId("player-1"), invalid, 0));
        }
    }
}
