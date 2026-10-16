package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.World;

import java.util.Objects;

/**
 * Sở hữu một World native; không tự tạo body/map hoặc triển khai gameplay.
 * Sau khi tích hợp runtime, chỉ simulation owner được step/mutate và dispose World.
 * Synchronized chỉ bảo vệ lifecycle wrapper, KHÔNG biến Box2D thành thread-safe.
 * Caller phải bảo đảm không có step/mutation đang chạy khi đóng tài nguyên.
 */
public final class HeadlessPhysicsWorld implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicInteger LIVE_WORLDS=new java.util.concurrent.atomic.AtomicInteger();
    public static int liveWorldCount() { return LIVE_WORLDS.get(); }
    private World world;
    private boolean disposed;
    private Object mapCollisionOwner;
    private Object playerPhysicsOwner;

    /** Chỉ factory tạo wrapper và chuyển quyền sở hữu World mới vào đây. */
    HeadlessPhysicsWorld(World world) {
        this.world = Objects.requireNonNull(world, "world không được null");
        LIVE_WORLDS.incrementAndGet();
    }

    /**
     * Cho mượn World trong phạm vi simulation; không chuyển quyền sở hữu native resource.
     * Không gọi World.dispose trực tiếp, không giữ/sử dụng reference sau khi wrapper đóng.
     *
     * @throws IllegalStateException nếu wrapper đã dispose
     */
    public synchronized World getWorld() {
        if (disposed) {
            throw new IllegalStateException("World đã được dispose");
        }
        return world;
    }

    public synchronized boolean isDisposed() {
        return disposed;
    }

    /** Giải phóng native đúng một lần; gọi lại sau khi dispose thành công là no-op. */
    public synchronized void dispose() {
        if (!disposed) {
            world.dispose();
            LIVE_WORLDS.decrementAndGet();
            disposed = true;
            world = null; // Không giữ Java Body/Fixture references của World đã dispose.
            mapCollisionOwner = null;
            playerPhysicsOwner = null;
        }
    }

    /** Hỗ trợ cleanup cả khi code trong try-with-resources ném exception. */
    @Override
    public void close() {
        dispose();
    }

    /** Marker theo World, chống build trùng kể cả caller dùng hai builder khác nhau. */
    synchronized void claimMapCollision(Object owner) {
        Objects.requireNonNull(owner, "owner không được null");
        getWorld();
        if (mapCollisionOwner != null) {
            throw new IllegalStateException("World đã có map collision hoặc đang build");
        }
        mapCollisionOwner = owner;
    }

    /** Chỉ handle sở hữu được giải phóng marker; World đã dispose không còn native bodies. */
    synchronized void releaseMapCollision(Object owner) {
        if (!disposed && mapCollisionOwner != owner) {
            throw new IllegalStateException("Không sở hữu map collision của World");
        }
        mapCollisionOwner = null;
    }

    synchronized void claimPlayerPhysics(Object owner) {
        Objects.requireNonNull(owner, "owner không được null");
        getWorld();
        if (playerPhysicsOwner != null) {
            throw new IllegalStateException("World đã có player physics hoặc đang build");
        }
        playerPhysicsOwner = owner;
    }

    synchronized void releasePlayerPhysics(Object owner) {
        if (!disposed && playerPhysicsOwner != owner) {
            throw new IllegalStateException("Không sở hữu player physics của World");
        }
        playerPhysicsOwner = null;
    }
}
