package com.smashprofs.server.game.physics;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpawnProviderTest {
    private final MapCollisionDefinition geometry = new TmxCollisionLoader().loadOriginalMap();

    @Test
    public void spawnPositionsAreValid() {
        try (HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
             var arena = new MapCollisionBuilder().build(physics, geometry)) {
            float skin = PlayerPhysicsFactory.arenaSkin(physics.getWorld(), 18);
            SpawnProvider provider = new SpawnProvider(geometry, skin);
            assertEquals(4.5f, provider.getSpawn(0).xMeters(), 0.0f);
            assertEquals(0.85f, provider.getSpawn(0).yMeters(), 0.0f);
            assertEquals(7.25f, provider.getSpawn(1).xMeters(), 0.0f);
            var rectangle41 = geometry.rectangles().stream().filter(rect -> rect.objectId() == 41).findFirst().orElseThrow();
            float expectedY = (rectangle41.yPixels() + rectangle41.heightPixels()) / 100
                    + PlayerPhysicsFactory.PLAYER_RADIUS_METERS + skin + 0.01f;
            assertEquals(expectedY, provider.getSpawn(1).yMeters(), 0.000001f);
            assertTrue(provider.getSpawn(1).yMeters() > 0.5f);
            assertEquals(5.36f, provider.getSpawn(2).xMeters(), 0.000001f);
            assertEquals(6.0f, provider.getSpawn(3).xMeters(), 0.000001f);
            assertEquals(0.23f, provider.getSpawn(2).yMeters(), 0.000001f);
            assertEquals(0.23f, provider.getSpawn(3).yMeters(), 0.000001f);
        }
    }

    @Test
    public void spawnDoesNotOverlapStaticColliders() {
        try (HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
             var arena = new MapCollisionBuilder().build(physics, geometry)) {
            float skin = PlayerPhysicsFactory.arenaSkin(physics.getWorld(), 18);
            SpawnProvider provider = new SpawnProvider(geometry, skin);
            for (int slot = 0; slot < 4; slot++) {
                var position = provider.getSpawn(slot);
                for (var rectangle : geometry.rectangles()) {
                    float nearestX = Math.max(rectangle.xPixels() / 100,
                            Math.min(position.xMeters(), (rectangle.xPixels() + rectangle.widthPixels()) / 100));
                    float nearestY = Math.max(rectangle.yPixels() / 100,
                            Math.min(position.yMeters(), (rectangle.yPixels() + rectangle.heightPixels()) / 100));
                    float dx = position.xMeters() - nearestX;
                    float dy = position.yMeters() - nearestY;
                    float radius = PlayerPhysicsFactory.PLAYER_RADIUS_METERS + skin;
                    assertTrue("slot=" + slot + ", object=" + rectangle.objectId(), dx * dx + dy * dy >= radius * radius);
                }
            }
        }
    }

    @Test
    public void allPlayersSpawnInsideArena() {
        try (HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
             var arena = new MapCollisionBuilder().build(physics, geometry)) {
            SpawnProvider provider = new SpawnProvider(geometry, PlayerPhysicsFactory.arenaSkin(physics.getWorld(), 18));
            for (int slot = 0; slot < 4; slot++) {
                var spawn = provider.getSpawn(slot);
                float radius = PlayerPhysicsFactory.PLAYER_RADIUS_METERS;
                assertTrue(spawn.xMeters() - radius >= 0);
                assertTrue(spawn.yMeters() - radius >= 0);
                assertTrue(spawn.xMeters() + radius <= geometry.mapWidthPixels() / 100);
                assertTrue(spawn.yMeters() + radius <= geometry.mapHeightPixels() / 100);
            }
        }
    }

    @Test
    public void invalidSlotIsRejected() {
        SpawnProvider provider = new SpawnProvider(geometry, 0.01f);
        assertThrows(IllegalArgumentException.class, () -> provider.getSpawn(-1));
        assertThrows(IllegalArgumentException.class, () -> provider.getSpawn(4));
    }

    @Test
    public void spawnDefinitionIsDeterministicAndImmutable() {
        SpawnProvider first = new SpawnProvider(geometry, 0.01f);
        SpawnProvider second = new SpawnProvider(geometry, 0.01f);
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(first.getSpawn(slot), second.getSpawn(slot));
        }
        assertThrows(IllegalArgumentException.class, () -> new SpawnProvider(geometry, Float.NaN));
    }
}
