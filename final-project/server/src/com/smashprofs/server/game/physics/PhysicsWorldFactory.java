package com.smashprofs.server.game.physics;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.World;

/** Tạo World rỗng độc lập, không khởi chạy LibGDX application hoặc tạo tài nguyên rendering. */
public final class PhysicsWorldFactory {
    /**
     * Khớp PlayScreen của baseline: gravity (0, 0), cho phép body ngủ.
     * Gravity riêng bằng impulse trong Player chưa được đưa vào server ở bước này.
     * Caller nhận quyền sở hữu và phải đóng wrapper, ưu tiên try-with-resources.
     */
    public HeadlessPhysicsWorld create() {
        return create(0.0f, 0.0f, true);
    }

    /**
     * Cho phép cấu hình gravity mà không giữ Vector2 mutable hoặc World dùng chung.
     * Native loader chọn thư viện theo hệ điều hành/kiến trúc từ runtime classpath.
     * Lỗi native được giữ nguyên, không mock hoặc fallback sang physics giả.
     */
    public HeadlessPhysicsWorld create(float gravityX, float gravityY, boolean doSleep) {
        if (!Float.isFinite(gravityX) || !Float.isFinite(gravityY)) {
            throw new IllegalArgumentException("Gravity phải là số hữu hạn");
        }
        Box2D.init();
        return new HeadlessPhysicsWorld(new World(new Vector2(gravityX, gravityY), doSleep));
    }
}
