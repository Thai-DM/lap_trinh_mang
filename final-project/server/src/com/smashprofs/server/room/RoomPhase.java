package com.smashprofs.server.room;

/** FINISHED là kết thúc gameplay, khác với room closed hoặc World đã dispose. */
public enum RoomPhase {
    WAITING,
    READY,
    COUNTDOWN,
    PLAYING,
    FINISHED
}
