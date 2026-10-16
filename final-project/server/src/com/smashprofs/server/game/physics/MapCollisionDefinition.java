package com.smashprofs.server.game.physics;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Geometry bất biến theo pixel, Y hướng lên như RectangleMapObject của LibGDX mặc định. */
public record MapCollisionDefinition(String sourceName, float mapWidthPixels, float mapHeightPixels,
                                     List<CollisionRectangle> rectangles) {
    public MapCollisionDefinition {
        Objects.requireNonNull(sourceName, "sourceName không được null");
        if (sourceName.isBlank() || !Float.isFinite(mapWidthPixels) || !Float.isFinite(mapHeightPixels)
                || mapWidthPixels <= 0 || mapHeightPixels <= 0) {
            throw new IllegalArgumentException("Tên nguồn và kích thước map phải hợp lệ");
        }
        rectangles = List.copyOf(rectangles);
        Set<Integer> ids = new HashSet<>();
        for (CollisionRectangle rectangle : rectangles) {
            if (!ids.add(rectangle.objectId())) {
                throw new IllegalArgumentException("Trùng collision object ID: " + rectangle.objectId());
            }
        }
    }

    /** Giữ cả kích thước bằng 0 của baseline; không sửa geometry bằng epsilon hoặc bỏ object. */
    public record CollisionRectangle(int objectId, float xPixels, float yPixels,
                                     float widthPixels, float heightPixels) {
        public CollisionRectangle {
            if (objectId <= 0 || !Float.isFinite(xPixels) || !Float.isFinite(yPixels)
                    || !Float.isFinite(widthPixels) || !Float.isFinite(heightPixels)
                    || widthPixels < 0 || heightPixels < 0) {
                throw new IllegalArgumentException("Collision rectangle không hợp lệ, ID=" + objectId);
            }
        }
    }
}
