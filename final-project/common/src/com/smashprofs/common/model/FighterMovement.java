package com.smashprofs.common.model;

/** The same fixed-step free movement is used by authority and local presentation prediction. */
public final class FighterMovement {
    public static final float DASH_SPEED=3.8f;
    public static final int DASH_TICKS=12, DASH_COOLDOWN_TICKS=24;
    public static final float DODGE_SPEED=3.8f;
    public static final float AIR_DODGE_SPEED=3.8f;
    public static final int AIR_DODGE_TICKS=12;
    private static final float BASE_RUN_SPEED=1.6f;
    private static final float GROUND_ACCELERATION=12;
    private static final float AIR_ACCELERATION=8;
    private FighterMovement() { }
    public record Velocity(float x,float y,boolean fastFalling) { }
    public static Velocity step(float x,float y,float axis,int speed,boolean grounded,boolean down,boolean shortHop,boolean canMove) {
        return step(x,y,axis,speed,grounded,down,shortHop,canMove,false);
    }
    public static Velocity step(float x,float y,float axis,int speed,boolean grounded,boolean down,boolean shortHop,boolean canMove,boolean sprint) {
        float max=BASE_RUN_SPEED*(.8f+speed*.04f)*(sprint?1.12f:1);
        if(canMove && Math.abs(axis)>.01f) {
            float target=axis*max,step=(grounded?GROUND_ACCELERATION:AIR_ACCELERATION)/60f;
            x=x<target?Math.min(target,x+step):Math.max(target,x-step);
        } else if(grounded || canMove) {
            // Releasing input should stop drift; locked airborne impulses keep their momentum.
            x*=grounded?.55f:.80f;if(Math.abs(x)<.02f) { x=0; }
        }
        if(shortHop && y>1.8f) { y=1.8f; }
        boolean fast=!grounded && y<0 && down;
        if(fast) { y=-9.6f; }
        return new Velocity(x,Math.max(fast?-9.6f:-6,y-9.8f/60),fast);
    }
}
