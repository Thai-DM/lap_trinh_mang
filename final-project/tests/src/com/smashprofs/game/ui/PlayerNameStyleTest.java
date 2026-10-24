package com.smashprofs.game.ui;

import com.badlogic.gdx.graphics.Color;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerNameStyleTest {

    @Test
    public void slotColorsMapToExpectedHues() {
        assertEquals(Color.valueOf("FFD400"), PlayerNameStyle.color(0)); // P1 Yellow
        assertEquals(Color.valueOf("FF3B30"), PlayerNameStyle.color(1)); // P2 Red
        assertEquals(Color.valueOf("3DDC57"), PlayerNameStyle.color(2)); // P3 Green
        assertEquals(Color.valueOf("3D8BFF"), PlayerNameStyle.color(3)); // P4 Blue
    }

    @Test
    public void slotColorsWrapModulo() {
        assertEquals(PlayerNameStyle.color(0), PlayerNameStyle.color(4));
        assertEquals(PlayerNameStyle.color(1), PlayerNameStyle.color(5));
        assertEquals(PlayerNameStyle.color(2), PlayerNameStyle.color(6));
        assertEquals(PlayerNameStyle.color(3), PlayerNameStyle.color(7));
    }

    @Test
    public void negativeSlotsDoNotThrowAndWrapCorrectly() {
        assertNotNull(PlayerNameStyle.color(-1));
        assertEquals(PlayerNameStyle.color(3), PlayerNameStyle.color(-1));
        assertEquals(PlayerNameStyle.color(2), PlayerNameStyle.color(-2));
    }
}
