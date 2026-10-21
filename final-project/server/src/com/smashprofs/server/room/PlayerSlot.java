package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;

import java.util.Objects;

/** Vị trí bất biến đã được gán cho một player, có chỉ số từ 0 đến 3. */
public record PlayerSlot(int slotIndex, PlayerId playerId) {
    public PlayerSlot {
        if (slotIndex < 0 || slotIndex > 3) {
            throw new IllegalArgumentException("slotIndex phải nằm trong khoảng 0 đến 3");
        }
        Objects.requireNonNull(playerId, "playerId không được null");
    }
}
