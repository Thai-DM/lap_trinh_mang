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
        boolean abilityPressed
) {
}
