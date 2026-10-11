package com.smashprofs.server.game.physics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.World;
import org.junit.Test;

import static org.junit.Assert.*;

/** Smoke test với JNI Box2D thật, không backend, desktop launcher, body, asset hoặc sleep. */
public class PhysicsWorldFactoryTest {
    private final PhysicsWorldFactory factory = new PhysicsWorldFactory();

    @Test
    public void box2dNativeLibraryLoadsSuccessfully() {
        Box2D.init();
        Box2D.init();
        // Một JNI call thật xác minh native đã load, không chỉ kiểm tra Java class tồn tại.
        try (HeadlessPhysicsWorld physics = factory.create()) {
            assertEquals(0, physics.getWorld().getBodyCount());
        }
    }

    @Test
    public void worldCanBeCreatedWithoutApplicationOrGraphics() {
        assertNoApplicationOrGraphics();
        try (HeadlessPhysicsWorld physics = factory.create()) {
            assertNotNull(physics.getWorld());
            assertFalse(physics.isDisposed());
            assertNoApplicationOrGraphics();
        }
        assertNoApplicationOrGraphics();
    }

    @Test
    public void defaultWorldMatchesBaselineGravity() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            assertGravity(physics.getWorld(), 0.0f, 0.0f);
        }
    }

    @Test
    public void worldHasConfiguredGravity() {
        try (HeadlessPhysicsWorld physics = factory.create(1.25f, -9.81f, true)) {
            assertGravity(physics.getWorld(), 1.25f, -9.81f);
        }
    }

    @Test
    public void worldCanBeCreatedWithSleepingDisabled() {
        // World 1.10.0 không có getter doSleep; kiểm chứng nhánh constructor bằng JNI thật.
        try (HeadlessPhysicsWorld physics = factory.create(0.0f, 0.0f, false)) {
            assertEquals(0, physics.getWorld().getBodyCount());
            assertGravity(physics.getWorld(), 0.0f, 0.0f);
        }
    }

    @Test
    public void emptyWorldCanStepAtFixedSixtyHzWithoutGraphics() {
        assertNoApplicationOrGraphics();
        try (HeadlessPhysicsWorld physics = factory.create()) {
            World world = physics.getWorld();
            for (int tick = 0; tick < 60; tick++) {
                world.step(1.0f / 60.0f, 6, 2);
            }
            assertEquals(0, world.getBodyCount());
            assertEquals(0, world.getContactCount());
            assertFalse(world.isLocked());
            assertGravity(world, 0.0f, 0.0f);
            assertNoApplicationOrGraphics();
        }
    }

    @Test
    public void twoWorldsAreIndependentInstances() {
        try (HeadlessPhysicsWorld first = factory.create();
             HeadlessPhysicsWorld second = factory.create()) {
            assertNotSame(first, second);
            assertNotSame(first.getWorld(), second.getWorld());
            first.getWorld().setGravity(new Vector2(3.0f, -4.0f));
            first.getWorld().setAutoClearForces(false);
            assertGravity(first.getWorld(), 3.0f, -4.0f);
            assertGravity(second.getWorld(), 0.0f, 0.0f);
            assertFalse(first.getWorld().getAutoClearForces());
            assertTrue(second.getWorld().getAutoClearForces());
            assertEquals(0, first.getWorld().getBodyCount());
            assertEquals(0, second.getWorld().getBodyCount());
        }
    }

    @Test
    public void disposingOneWorldDoesNotAffectAnother() {
        try (HeadlessPhysicsWorld first = factory.create();
             HeadlessPhysicsWorld second = factory.create(0.0f, -2.0f, true)) {
            first.dispose();
            assertTrue(first.isDisposed());
            assertFalse(second.isDisposed());
            second.getWorld().step(1.0f / 60.0f, 6, 2);
            assertGravity(second.getWorld(), 0.0f, -2.0f);
        }
    }

    @Test
    public void worldCanBeDisposedSuccessfully() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            physics.dispose();
            assertTrue(physics.isDisposed());
        }
    }

    @Test
    public void repeatedDisposeAndCloseAreSafe() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            physics.dispose();
            physics.dispose();
            physics.close();
            physics.close();
            assertTrue(physics.isDisposed());
        }
    }

    @Test
    public void worldCannotBeAccessedAfterDispose() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            physics.dispose();
            assertThrows(IllegalStateException.class, physics::getWorld);
        }
    }

    @Test
    public void tryWithResourcesClosesWorld() {
        HeadlessPhysicsWorld physics = factory.create();
        try (physics) {
            assertFalse(physics.isDisposed());
        }
        assertTrue(physics.isDisposed());
        assertThrows(IllegalStateException.class, physics::getWorld);
    }

    @Test
    public void tryWithResourcesClosesWorldWhenCallerThrows() {
        HeadlessPhysicsWorld physics = factory.create();
        RuntimeException failure = new IllegalStateException("Lỗi giả trong code caller");
        assertSame(failure, assertThrows(RuntimeException.class, () -> {
            try (physics) {
                physics.getWorld().step(1.0f / 60.0f, 6, 2);
                throw failure;
            }
        }));
        assertTrue(physics.isDisposed());
        assertThrows(IllegalStateException.class, physics::getWorld);
    }

    @Test
    public void nonFiniteGravityIsRejected() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(invalid, 0.0f, true));
            assertThrows(IllegalArgumentException.class, () -> factory.create(0.0f, invalid, true));
        }
    }

    private static void assertGravity(World world, float x, float y) {
        Vector2 gravity = world.getGravity();
        assertEquals(x, gravity.x, 0.0f);
        assertEquals(y, gravity.y, 0.0f);
    }

    private static void assertNoApplicationOrGraphics() {
        assertNull(Gdx.app);
        assertNull(Gdx.graphics);
        assertNull(Gdx.gl);
        assertNull(Gdx.gl20);
        assertNull(Gdx.gl30);
    }
}
