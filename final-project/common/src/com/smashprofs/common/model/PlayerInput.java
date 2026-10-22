package com.smashprofs.common.model;

/** Input command submitted by one player for authoritative processing. */
public record PlayerInput(
        PlayerId playerId,
        long sequence,
        long clientTick,
        float moveAxis,
        boolean jumpPressed,
        boolean stompPressed,
        boolean meleePressed,
        boolean blockHeld,
        boolean abilityPressed,
        boolean interactPressed,
        boolean dropPressed,
        FighterInput fighter
) {
    public PlayerInput { if(fighter==null) { throw new IllegalArgumentException("Missing fighter input"); } }
    public PlayerInput(PlayerId id,long sequence,long tick,float axis,boolean jump,boolean stomp,boolean melee,
                       boolean block,boolean ability,boolean interact,boolean drop) {
        this(id,sequence,tick,axis,jump,stomp,melee,block,ability,interact,drop,FighterInput.NONE);
    }
    /** Existing v1 callers default the optional inventory edges to false. */
    public PlayerInput(PlayerId playerId, long sequence, long clientTick, float moveAxis,
                       boolean jumpPressed, boolean stompPressed, boolean meleePressed,
                       boolean blockHeld, boolean abilityPressed) {
        this(playerId, sequence, clientTick, moveAxis, jumpPressed, stompPressed, meleePressed,
                blockHeld, abilityPressed, false, false);
    }
}
