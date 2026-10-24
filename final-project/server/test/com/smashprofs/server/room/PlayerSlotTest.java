package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class PlayerSlotTest {

    @Test
    public void slotZeroIsValid() {
        PlayerSlot slot = new PlayerSlot(0, new PlayerId("player-1"));

        assertEquals(0, slot.slotIndex());
    }

    @Test
    public void slotThreeIsValid() {
        PlayerSlot slot = new PlayerSlot(3, new PlayerId("player-1"));

        assertEquals(3, slot.slotIndex());
    }

    @Test
    public void negativeSlotIndexIsRejected() {
        for (int index : new int[]{-1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlayerSlot(index, new PlayerId("player-1")));
        }
    }

    @Test
    public void slotIndexGreaterThanThreeIsRejected() {
        for (int index : new int[]{4, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlayerSlot(index, new PlayerId("player-1")));
        }
    }

    @Test
    public void nullPlayerIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new PlayerSlot(0, null));
    }

    @Test
    public void playerSlotRetainsCorrectPlayerId() {
        PlayerId playerId = new PlayerId("player-2");

        PlayerSlot slot = new PlayerSlot(1, playerId);

        assertSame(playerId, slot.playerId());
    }

    @Test
    public void playerSlotRetainsCorrectSlotIndex() {
        for (int index = 0; index <= 3; index++) {
            PlayerSlot slot = new PlayerSlot(index, new PlayerId("player-1"));

            assertEquals(index, slot.slotIndex());
        }
    }
}
