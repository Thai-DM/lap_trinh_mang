package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchSetup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Circle/fixture/contacts dùng JNI thật; không render hoặc gameplay input. */
public class PlayerPhysicsFactoryTest {
    private HeadlessPhysicsWorld physics;
    private MapCollisionDefinition geometry;
    private MapCollisionBuilder.MapCollisionBodies arena;
    private PlayerPhysicsFactory.PlayerPhysicsBodies players;

    @Before
    public void setUp() {
        geometry = new TmxCollisionLoader().loadOriginalMap();
        physics = new PhysicsWorldFactory().create();
        arena = new MapCollisionBuilder().build(physics, geometry);
    }

    @After
    public void tearDown() {
        physics.close();
        if (players != null) {
            players.close();
        }
        arena.close();
    }

    @Test
    public void eachPlayerHasExactlyOneBody() {
        create(4);
        assertEquals(4, players.getPlayerCount());
        assertEquals(22, physics.getWorld().getBodyCount());
        assertEquals(22, physics.getWorld().getFixtureCount());
        for (int slot = 0; slot < 4; slot++) {
            assertNotNull(players.getBody(id(slot)));
            assertEquals(1, players.getBody(id(slot)).getFixtureList().size);
        }
    }

    @Test
    public void playerBodyIsDynamic() {
        create(4);
        for (int slot = 0; slot < 4; slot++) {
            Body body = players.getBody(id(slot));
            assertEquals(BodyDef.BodyType.DynamicBody, body.getType());
            assertTrue(body.isFixedRotation());
            assertEquals(0.0f, body.getLinearDamping(), 0.0f);
            assertEquals(0.0f, body.getAngularDamping(), 0.0f);
            assertEquals(1.0f, body.getGravityScale(), 0.0f);
        }
    }

    @Test
    public void playerShapeMatchesOriginal() {
        create(4);
        for (int slot = 0; slot < 4; slot++) {
            Shape shape = players.getBody(id(slot)).getFixtureList().first().getShape();
            assertTrue(shape instanceof CircleShape);
            assertEquals(5.0f / 100.0f, shape.getRadius(), 0.0f);
        }
    }

    @Test
    public void playerFixtureMatchesOriginal() {
        create(4);
        FixtureDef defaults = new FixtureDef();
        for (int slot = 0; slot < 4; slot++) {
            Fixture fixture = players.getBody(id(slot)).getFixtureList().first();
            assertEquals(defaults.density, fixture.getDensity(), 0.0f);
            assertEquals(defaults.friction, fixture.getFriction(), 0.0f);
            assertEquals(defaults.restitution, fixture.getRestitution(), 0.0f);
            assertEquals(defaults.isSensor, fixture.isSensor());
            assertEquals(1, fixture.getFilterData().categoryBits);
            assertEquals(4 | 3 | 2, fixture.getFilterData().maskBits);
            assertEquals(-1, fixture.getFilterData().groupIndex);
            assertNull(fixture.getUserData());
        }
    }

    @Test
    public void playerBodyUsesCorrectPlayerId() {
        create(4);
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(id(slot), players.getBody(id(slot)).getUserData());
        }
        assertThrows(UnsupportedOperationException.class, () -> players.getPlayerIds().clear());
    }

    @Test
    public void playerBodyUsesCorrectSlot() {
        MatchSetup unordered = new MatchSetup("match", "room", List.of(
                new MatchSetup.Participant(id(3), 3, "Viktor"),
                new MatchSetup.Participant(id(0), 0, "Alex"),
                new MatchSetup.Participant(id(2), 2, "Leo")));
        players = new PlayerPhysicsFactory().build(physics, unordered, geometry);
        SpawnProvider spawns = new SpawnProvider(geometry, PlayerPhysicsFactory.arenaSkin(physics.getWorld(), 18));
        for (var participant : unordered.participants()) {
            Body body = players.getBody(participant.playerId());
            var spawn = spawns.getSpawn(participant.slotIndex());
            assertEquals(spawn.xMeters(), body.getPosition().x, 0.0f);
            assertEquals(spawn.yMeters(), body.getPosition().y, 0.0f);
        }
    }

    @Test
    public void playerBodiesBelongToCorrectWorld() {
        create(4);
        for (int slot = 0; slot < 4; slot++) {
            assertSame(physics.getWorld(), players.getBody(id(slot)).getWorld());
        }
    }

    @Test
    public void twoMatchesHaveIndependentBodies() {
        create(4);
        try (HeadlessPhysicsWorld second = new PhysicsWorldFactory().create();
             var secondArena = new MapCollisionBuilder().build(second, geometry);
             var secondPlayers = new PlayerPhysicsFactory().build(second, setup(4), geometry)) {
            for (int slot = 0; slot < 4; slot++) {
                assertNotSame(players.getBody(id(slot)), secondPlayers.getBody(id(slot)));
                assertSame(second.getWorld(), secondPlayers.getBody(id(slot)).getWorld());
            }
            physics.close();
            second.getWorld().step(1.0f / 60.0f, 6, 2);
            assertEquals(22, second.getWorld().getBodyCount());
        }
    }

    @Test
    public void duplicateBuildIsRejected() {
        create(2);
        assertThrows(IllegalStateException.class,
                () -> new PlayerPhysicsFactory().build(physics, setup(2), geometry));
        assertEquals(20, physics.getWorld().getBodyCount());
    }

    @Test
    public void partialPlayerBuildFailureRollsBackOnlyPlayerBodies() {
        AtomicInteger fixtures = new AtomicInteger();
        PlayerPhysicsFactory failing = new PlayerPhysicsFactory((body, fixture) -> {
            body.createFixture(fixture);
            if (fixtures.incrementAndGet() == 2) {
                throw new IllegalStateException("test-player-fixture-failure");
            }
        });
        assertThrows(IllegalStateException.class, () -> failing.build(physics, setup(3), geometry));
        assertEquals(18, physics.getWorld().getBodyCount());
        assertEquals(18, physics.getWorld().getFixtureCount());
        create(3); // Marker cũng phải được rollback để build lại hợp lệ.
        assertEquals(21, physics.getWorld().getBodyCount());
    }

    @Test
    public void playerCleanupIsIdempotentAndPreservesArena() {
        create(4);
        players.close();
        players.close();
        assertEquals(0, players.getPlayerCount());
        assertEquals(18, physics.getWorld().getBodyCount());
        assertThrows(IllegalStateException.class, () -> players.getBody(id(0)));
    }

    @Test
    public void allRosterSizesRemainStableWithoutManualGravity() {
        for (int count = 2; count <= 4; count++) {
            try (HeadlessPhysicsWorld world = new PhysicsWorldFactory().create();
                 var map = new MapCollisionBuilder().build(world, geometry);
                 var roster = new PlayerPhysicsFactory().build(world, setup(count), geometry)) {
                SpawnProvider spawns = new SpawnProvider(geometry, PlayerPhysicsFactory.arenaSkin(world.getWorld(), 18));
                for (int tick = 0; tick < 60; tick++) {
                    world.getWorld().step(1.0f / 60.0f, 6, 2);
                    for (var contact : world.getWorld().getContactList()) {
                        assertFalse("Spawn chồng collider", contact.isTouching());
                    }
                }
                for (int slot = 0; slot < count; slot++) {
                    Body body = roster.getBody(id(slot));
                    assertEquals(spawns.getSpawn(slot).xMeters(), body.getPosition().x, 0.000001f);
                    assertEquals(spawns.getSpawn(slot).yMeters(), body.getPosition().y, 0.000001f);
                }
            }
        }
    }

    @Test
    public void negativeGroupPreventsPlayerPlayerContact() {
        create(2);
        players.getBody(id(0)).setTransform(1, 3, 0);
        players.getBody(id(1)).setTransform(1, 3, 0);
        physics.getWorld().step(1.0f / 60.0f, 6, 2);
        for (var contact : physics.getWorld().getContactList()) {
            assertFalse(contact.getFixtureA().getBody().getUserData() instanceof PlayerId
                    && contact.getFixtureB().getBody().getUserData() instanceof PlayerId);
        }
    }

    @Test
    public void mutableBodyIsNotPubliclyExposed() {
        assertThrows(NoSuchMethodException.class,
                () -> PlayerPhysicsFactory.PlayerPhysicsBodies.class.getMethod("getBody", PlayerId.class));
    }

    private void create(int count) {
        players = new PlayerPhysicsFactory().build(physics, setup(count), geometry);
    }

    private static MatchSetup setup(int count) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        String[] characters = {"Alex", "Luca", "Leo", "Viktor"};
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        return new MatchSetup("match", "room", participants);
    }

    private static PlayerId id(int slot) {
        return new PlayerId("arbitrary-player-" + slot);
    }
}
