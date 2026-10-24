package com.smashprofs.game.ui;

import com.badlogic.gdx.graphics.Color;

/** In-match name colours by slot index: P1 yellow, P2 red, P3 green, P4 blue. Presentation only. */
public final class PlayerNameStyle {
    private static final Color[] SLOT_COLORS={Color.valueOf("FFD400"),Color.valueOf("FF3B30"),Color.valueOf("3DDC57"),Color.valueOf("3D8BFF")};
    private PlayerNameStyle() { }
    public static Color color(int slotIndex) { return SLOT_COLORS[Math.floorMod(slotIndex,SLOT_COLORS.length)]; }
}
