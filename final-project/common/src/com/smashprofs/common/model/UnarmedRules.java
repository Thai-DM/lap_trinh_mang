package com.smashprofs.common.model;

/** Prototype balance, not official Brawlhalla frame data. All durations use the 60 Hz simulation. */
public final class UnarmedRules {
    public static final int AIR_JUMPS=2, AIR_ACTIONS=3;
    public static final int INPUT_BUFFER=5, GC_WINDOW=18, DODGE_STARTUP=2, DODGE_RECOVERY=4;
    public static final int GROUND_IFRAMES=16, AIR_SPOT_IFRAMES=20, AIR_DIRECTION_IFRAMES=12;
    public static final int GROUND_COOLDOWN=60, AIR_COOLDOWN=163;
    public static final int CHASE_WINDOW=30, CHASE_CHARGES=2, CHASE_FRAMES=17;
    public static final int HEAVY_HITSTOP=2;
    public static final float CHASE_SPEED=3.2f, EXHAUSTED_RECOVERY_SPEED=3.2f;
    public static final int WALL_WARNING=5, WALL_DANGER=7, WALL_SLIP=9;
    private UnarmedRules() { }
    public static int inputBufferFrames() { return Math.max(0,Math.min(12,Integer.getInteger("smashprofs.unarmed.inputBufferFrames",INPUT_BUFFER))); }
    public static int invulnerableFrames(String kind) {
        return switch(kind) { case "GROUND_SPOT"->GROUND_IFRAMES;case "AIR_SPOT"->AIR_SPOT_IFRAMES;case "CHASE"->CHASE_FRAMES;default->AIR_DIRECTION_IFRAMES; };
    }
    public static int totalDodgeFrames(String kind) { return DODGE_STARTUP+invulnerableFrames(kind)+DODGE_RECOVERY; }
}
