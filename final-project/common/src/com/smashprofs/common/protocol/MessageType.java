package com.smashprofs.common.protocol;

/** Message types defined by protocol version 0.1. */
public enum MessageType {
    JOIN_ROOM,
    PLAYER_READY,
    PLAYER_INPUT,
    GAME_STATE,
    PING,
    PONG,
    GAME_END,
    MATCH_RESULT,
    RANKING_UPDATED,
    ERROR
}
