package com.smashprofs.server.game.physics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.game.MatchSetup;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Map thật và World thật; không gắn map vào runtime production. */
public class MapCollisionBuilderTest {
    private final PhysicsWorldFactory factory = new PhysicsWorldFactory();
    private final MapCollisionDefinition definition = new TmxCollisionLoader().loadOriginalMap();
    private final MapCollisionBuilder builder = new MapCollisionBuilder();

    @Test
    public void staticBodiesMatchCollisionObjects() throws Exception {
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            assertEquals(18, collision.getBodyCount());
            assertEquals(18, physics.getWorld().getBodyCount());
            assertEquals(18, physics.getWorld().getFixtureCount());
            List<Body> bodies = bodies(physics.getWorld());
            for (var rectangle : TmxCollisionLoaderTest.originalRectangles()) {
                Body body = matchingBody(bodies, rectangle);
                assertEquals(BodyDef.BodyType.StaticBody, body.getType());
                assertEquals(0.0f, body.getAngle(), 0.0f);
                assertEquals(1, body.getFixtureList().size);
                PolygonShape shape = (PolygonShape) body.getFixtureList().first().getShape();
                assertEquals(4, shape.getVertexCount());
                float halfWidth = rectangle.widthPixels() / 2 / 100.0f;
                float halfHeight = rectangle.heightPixels() / 2 / 100.0f;
                float[][] expected = {{-halfWidth, -halfHeight}, {halfWidth, -halfHeight},
                        {halfWidth, halfHeight}, {-halfWidth, halfHeight}};
                for (int index = 0; index < 4; index++) {
                    Vector2 vertex = new Vector2();
                    shape.getVertex(index, vertex);
                    assertEquals(expected[index][0], vertex.x, 0.0f);
                    assertEquals(expected[index][1], vertex.y, 0.0f);
                }
                bodies.remove(body);
            }
            assertTrue(bodies.isEmpty());
        }
    }

    @Test
    public void fixtureSettingsMatchOriginal() {
        FixtureDef original = new FixtureDef();
        original.filter.categoryBits = 2;
        original.filter.maskBits = 1 | 3;
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            for (Body body : bodies(physics.getWorld())) {
                Fixture fixture = body.getFixtureList().first();
                assertEquals(original.density, fixture.getDensity(), 0.0f);
                assertEquals(original.friction, fixture.getFriction(), 0.0f);
                assertEquals(original.restitution, fixture.getRestitution(), 0.0f);
                assertEquals(original.isSensor, fixture.isSensor());
                assertEquals(original.filter.categoryBits, fixture.getFilterData().categoryBits);
                assertEquals(original.filter.maskBits, fixture.getFilterData().maskBits);
                assertEquals(original.filter.groupIndex, fixture.getFilterData().groupIndex);
            }
        }
    }

    @Test
    public void userDataMatchesOriginal() {
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            for (Body body : bodies(physics.getWorld())) {
                assertEquals("Tile", body.getUserData());
                assertNull(body.getFixtureList().first().getUserData());
            }
        }
    }

    @Test
    public void duplicateBuildIsRejected() {
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            assertThrows(IllegalStateException.class, () -> builder.build(physics, definition));
            assertThrows(IllegalStateException.class, () -> new MapCollisionBuilder().build(physics, definition));
            assertEquals(18, collision.getBodyCount());
            assertEquals(18, physics.getWorld().getBodyCount());
        }
    }

    @Test
    public void separateWorldsHaveSeparateBodies() {
        try (HeadlessPhysicsWorld first = factory.create();
             HeadlessPhysicsWorld second = factory.create();
             var firstCollision = builder.build(first, definition);
             var secondCollision = builder.build(second, definition)) {
            for (Body firstBody : bodies(first.getWorld())) {
                assertSame(first.getWorld(), firstBody.getWorld());
                for (Body secondBody : bodies(second.getWorld())) {
                    assertNotSame(firstBody, secondBody);
                    assertSame(second.getWorld(), secondBody.getWorld());
                }
            }
            firstCollision.close();
            assertEquals(0, first.getWorld().getBodyCount());
            assertEquals(18, second.getWorld().getBodyCount());
            second.getWorld().step(1.0f / 60.0f, 6, 2);
            assertFalse(secondCollision.isClosed());
        }
    }

    @Test
    public void cleanupIsIdempotent() {
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            collision.close();
            collision.close();
            assertTrue(collision.isClosed());
            assertEquals(0, collision.getBodyCount());
            assertEquals(0, physics.getWorld().getBodyCount());
            assertEquals(0, physics.getWorld().getFixtureCount());
            assertFalse(physics.isDisposed());
            physics.getWorld().step(1.0f / 60.0f, 6, 2);
        }
    }

    @Test
    public void partialFailureDoesNotLeakBodies() {
        AtomicInteger fixtures = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("test-partial-build-failure");
        MapCollisionBuilder failing = new MapCollisionBuilder((body, fixture) -> {
            body.createFixture(fixture);
            if (fixtures.incrementAndGet() == 4) {
                throw failure;
            }
        });
        try (HeadlessPhysicsWorld physics = factory.create()) {
            Body existing = physics.getWorld().createBody(new BodyDef());
            existing.setUserData("Caller-owned");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> failing.build(physics, definition)));
            assertEquals(4, fixtures.get());
            assertEquals(1, physics.getWorld().getBodyCount());
            assertEquals(0, physics.getWorld().getFixtureCount());
            assertSame(existing, bodies(physics.getWorld()).get(0));
            try (var collision = builder.build(physics, definition)) {
                assertEquals(19, physics.getWorld().getBodyCount());
            }
            assertEquals(1, physics.getWorld().getBodyCount());
        }
    }

    @Test
    public void headlessWorldSmokeTestPasses() {
        assertNull(Gdx.app);
        assertNull(Gdx.graphics);
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            assertEquals(0.0f, physics.getWorld().getGravity().x, 0.0f);
            assertEquals(0.0f, physics.getWorld().getGravity().y, 0.0f);
            for (int tick = 0; tick < 60; tick++) {
                physics.getWorld().step(1.0f / 60.0f, 6, 2);
            }
            assertEquals(18, physics.getWorld().getBodyCount());
            assertEquals(18, collision.getBodyCount());
            assertNull(Gdx.gl);
            assertNull(Gdx.graphics);
        }
    }

    @Test
    public void previousServerTestsStillPass() {
        MatchSetup setup = new MatchSetup("match", "room", List.of(
                new MatchSetup.Participant(new PlayerId("alice"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("bob"), 1, "Luca")));
        try (MatchRuntime runtime = new MatchRuntime(setup)) {
            runtime.start();
            assertTrue(runtime.tick().isEmpty());
            assertEquals(1, runtime.getMatchState().getServerTick());
        }
    }

    @Test
    public void mapCanBeRebuiltAfterCleanup() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            var first = builder.build(physics, definition);
            first.close();
            try (var second = new MapCollisionBuilder().build(physics, definition)) {
                first.close();
                assertEquals(18, physics.getWorld().getBodyCount());
                assertEquals(18, second.getBodyCount());
            }
        }
    }

    @Test
    public void cleanupAfterCallerDisposesWorldIsSafe() {
        MapCollisionBuilder.MapCollisionBodies collision;
        try (HeadlessPhysicsWorld physics = factory.create()) {
            collision = builder.build(physics, definition);
        }
        collision.close();
        collision.close();
        assertTrue(collision.isClosed());
        assertEquals(0, collision.getBodyCount());
    }

    @Test
    public void cannotBuildIntoDisposedWorld() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            physics.close();
            assertThrows(IllegalStateException.class, () -> builder.build(physics, definition));
        }
    }

    @Test
    public void cleanupOnlyDestroysBodiesOwnedByThisBuild() {
        try (HeadlessPhysicsWorld physics = factory.create()) {
            Body existing = physics.getWorld().createBody(new BodyDef());
            try (var collision = builder.build(physics, definition)) {
                assertEquals(19, physics.getWorld().getBodyCount());
            }
            assertEquals(List.of(existing), bodies(physics.getWorld()));
        }
    }

    @Test
    public void originalZeroHeightObjectCreatesFixtureWithoutGeometryChange() {
        var rectangle = definition.rectangles().stream().filter(value -> value.objectId() == 35).findFirst().orElseThrow();
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            Body body = matchingBody(bodies(physics.getWorld()), rectangle);
            PolygonShape shape = (PolygonShape) body.getFixtureList().first().getShape();
            for (int index = 0; index < shape.getVertexCount(); index++) {
                Vector2 vertex = new Vector2();
                shape.getVertex(index, vertex);
                assertEquals(0.0f, vertex.y, 0.0f);
            }
            assertEquals(18, collision.getBodyCount());
        }
    }

    @Test
    public void cleanupDuringWorldStepIsRejectedWithoutDestroyingBodies() {
        try (HeadlessPhysicsWorld physics = factory.create();
             var collision = builder.build(physics, definition)) {
            World world = physics.getWorld();
            var rectangle = definition.rectangles().get(0);
            BodyDef dynamic = new BodyDef();
            dynamic.type = BodyDef.BodyType.DynamicBody;
            dynamic.position.set((rectangle.xPixels() + rectangle.widthPixels() / 2) / 100,
                    (rectangle.yPixels() + rectangle.heightPixels() / 2) / 100);
            Body body = world.createBody(dynamic);
            CircleShape circle = new CircleShape();
            try {
                circle.setRadius(0.03f);
                body.createFixture(circle, 1);
            } finally {
                circle.dispose();
            }
            AtomicBoolean attempted = new AtomicBoolean();
            world.setContactListener(new ContactListener() {
                @Override
                public void beginContact(Contact contact) {
                    assertTrue(world.isLocked());
                    assertThrows(IllegalStateException.class, collision::close);
                    assertEquals(19, world.getBodyCount());
                    attempted.set(true);
                }

                @Override
                public void endContact(Contact contact) { }

                @Override
                public void preSolve(Contact contact, Manifold oldManifold) { }

                @Override
                public void postSolve(Contact contact, ContactImpulse impulse) { }
            });
            world.step(1.0f / 60.0f, 6, 2);
            assertTrue(attempted.get());
            assertFalse(collision.isClosed());
            assertEquals(18, collision.getBodyCount());
            world.setContactListener(null);
        }
    }

    private static List<Body> bodies(World world) {
        Array<Body> array = new Array<>();
        world.getBodies(array);
        List<Body> result = new ArrayList<>();
        for (Body body : array) {
            result.add(body);
        }
        return result;
    }

    private static Body matchingBody(List<Body> bodies, MapCollisionDefinition.CollisionRectangle rectangle) {
        float x = (rectangle.xPixels() + rectangle.widthPixels() / 2) / 100.0f;
        float y = (rectangle.yPixels() + rectangle.heightPixels() / 2) / 100.0f;
        return bodies.stream().filter(body -> body.getPosition().x == x && body.getPosition().y == y)
                .findFirst().orElseThrow(() -> new AssertionError("Thiếu body cho object " + rectangle.objectId()));
    }
}
