package com.smashprofs.common.model;

/** Authoritative state of one player at a server tick. */
public record PlayerState(
        PlayerId playerId,
        String characterId,
        float x,
        float y,
        float velocityX,
        float velocityY,
        int hp,
        boolean grounded,
        boolean blocking,
        boolean dead,
        boolean facingRight
) {
}
