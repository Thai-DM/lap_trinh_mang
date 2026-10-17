package com.smashprofs.common.model;

/** Shared lava boundary and rebound used by authority and movement prediction. */
public final class FighterArena {
    public static final float LAVA_TOP=.16f;
    public static final float LAVA_CENTER_Y=LAVA_TOP+.055f;
    public static final float LAVA_BOUNCE_SPEED=2.8f;
    public static final int LAVA_STUN_TICKS=8;
    public static boolean touchesLava(float x,float y,float velocityY) {
        return x>=1 && x<=11.5f && y>=-1.5f && y-.05f<=LAVA_TOP+.002f && velocityY<=0;
    }
    private FighterArena() { }
}
