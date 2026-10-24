package com.smashprofs.common.model;

/** Direction policy is independent of keyboard mapping, facing, animation and physics. */
public final class UnarmedInputResolver {
    public enum Direction { NEUTRAL, SIDE, DOWN }
    private UnarmedInputResolver() { }
    public static Direction direction(float horizontal,boolean down,boolean aimUp,boolean prioritizeNeutral) {
        if(down) { return Direction.DOWN; }
        if(aimUp && prioritizeNeutral) { return Direction.NEUTRAL; }
        return Math.abs(horizontal)>.01f?Direction.SIDE:Direction.NEUTRAL;
    }
    public static String move(boolean grounded,boolean heavy,Direction direction) {
        if(!grounded && heavy) { return direction==Direction.DOWN?"GROUND_POUND":"RECOVERY"; }
        String prefix=switch(direction) { case DOWN->"D";case SIDE->"S";case NEUTRAL->"N"; };
        return prefix+(grounded?(heavy?"HEAVY":"LIGHT"):"AIR");
    }
    public static boolean hasAction(PlayerInput input) {
        return input!=null && (input.jumpPressed() || input.meleePressed() || input.fighter().heavyPressed() || input.fighter().dodgePressed() || input.fighter().dashPressed());
    }
}
