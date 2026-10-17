package com.smashprofs.common.protocol;

/** Giữ tên v0.1 và bổ sung tên TCP v1; wire dùng tên, không dùng ordinal. */
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
    ERROR,
    CONNECT,
    CONNECT_ACK,
    CREATE_ROOM,
    ROOM_CREATED,
    ROOM_STATE,
    LEAVE_ROOM,
    GAME_START,
    ROOM_SETTINGS_UPDATE,
    ROOM_SETTINGS_STATE,
    MATCH_EVENT,
    RTT_STATE,
    LOAD_READY,
    REMATCH_REQUEST,
    REMATCH_STATE
}
