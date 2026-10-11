package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchSetup;
import com.smashprofs.server.game.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/** Chỉ port Player.definePlayer; không animation, input, audio, movement hoặc gravity thủ công. */
public final class PlayerPhysicsFactory {
    public static final float PLAYER_RADIUS_METERS = 5.0f / MapCollisionBuilder.PIXELS_PER_METER;
    private final BiConsumer<Body, FixtureDef> fixtureCreator;

    public PlayerPhysicsFactory() {
        this(Body::createFixture);
    }

    /** Seam nội bộ cho lỗi fixture; không mock toàn bộ Box2D. */
    PlayerPhysicsFactory(BiConsumer<Body, FixtureDef> fixtureCreator) {
        this.fixtureCreator = Objects.requireNonNull(fixtureCreator, "fixtureCreator không được null");
    }

    /** Caller phải đang trong initialization/simulation ownership, không đồng thời World.step. */
    public PlayerPhysicsBodies build(HeadlessPhysicsWorld physics, MatchSetup setup, MapCollisionDefinition geometry) {
        Objects.requireNonNull(physics, "physics không được null");
        Objects.requireNonNull(setup, "setup không được null");
        Objects.requireNonNull(geometry, "geometry không được null");
        // Tái dùng validation character/ID/slot của ServerPlayer trước khi tạo bất kỳ native body nào.
        for (var participant : setup.participants()) {
            new ServerPlayer(participant.playerId(), participant.characterId(), participant.slotIndex());
        }
        synchronized (physics) {
            World world = physics.getWorld();
            requireUnlocked(world);
            SpawnProvider spawns = new SpawnProvider(geometry, arenaSkin(world, geometry.rectangles().size()));
            PlayerPhysicsBodies players = new PlayerPhysicsBodies(physics);
            physics.claimPlayerPhysics(players);
            CircleShape shape = null;
            try {
                shape = new CircleShape();
                shape.setRadius(PLAYER_RADIUS_METERS);
                FixtureDef fixture = new FixtureDef();
                fixture.shape = shape;
                fixture.filter.categoryBits = MapCollisionBuilder.PLAYER_ENTITY;
                fixture.filter.maskBits = 0b100 | MapCollisionBuilder.PROJECTILE_ENTITY | MapCollisionBuilder.WORLD_ENTITY;
                fixture.filter.groupIndex = -1;
                // Density/friction/restitution/sensor giữ đúng defaults trong Player.definePlayer.
                for (var participant : setup.participants()) {
                    var spawn = spawns.getSpawn(participant.slotIndex());
                    BodyDef definition = new BodyDef();
                    definition.type = BodyDef.BodyType.DynamicBody;
                    definition.fixedRotation = true;
                    definition.position.set(spawn.xMeters(), spawn.yMeters());
                    Body body = world.createBody(definition);
                    players.bodies.put(participant.playerId(), body);
                    // Thay nhãn local PlayerOne/PlayerTwo bằng PlayerId server; fixture userData vẫn null.
                    body.setUserData(participant.playerId());
                    fixtureCreator.accept(body, fixture);
                }
                return players;
            } catch (RuntimeException | Error failure) {
                try {
                    players.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            } finally {
                if (shape != null) {
                    shape.dispose();
                }
            }
        }
    }

    /** Đọc skin từ fixture arena native thật, không đoán hằng số nội bộ Box2D. */
    static float arenaSkin(World world, int expectedCount) {
        Array<Body> bodies = new Array<>();
        world.getBodies(bodies);
        int count = 0;
        float skin = 0;
        for (Body body : bodies) {
            if (body.getType() == BodyDef.BodyType.StaticBody && "Tile".equals(body.getUserData())) {
                count++;
                for (var fixture : body.getFixtureList()) {
                    skin = Math.max(skin, fixture.getShape().getRadius());
                }
            }
        }
        if (count != expectedCount) {
            throw new IllegalStateException("Arena collision chưa được build đúng trước player bodies");
        }
        return skin;
    }

    private static void requireUnlocked(World world) {
        if (world.isLocked()) {
            throw new IllegalStateException("Không tạo/destroy player body trong World.step");
        }
    }

    /** Runtime sở hữu wrapper; không có public getter trả Body hoặc mutable Map. */
    public static final class PlayerPhysicsBodies implements AutoCloseable {
        private final HeadlessPhysicsWorld physics;
        private final Map<PlayerId, Body> bodies = new LinkedHashMap<>();
        private boolean closed;

        private PlayerPhysicsBodies(HeadlessPhysicsWorld physics) {
            this.physics = physics;
        }

        public Set<PlayerId> getPlayerIds() {
            synchronized (physics) {
                return closed || physics.isDisposed() ? Set.of() : Set.copyOf(bodies.keySet());
            }
        }

        public int getPlayerCount() {
            return getPlayerIds().size();
        }

        /** Chỉ simulation owner đọc tâm Body; trả giá trị bất biến, không lộ Body/Vector2 mutable. */
        public PhysicsPosition getPosition(PlayerId playerId) {
            synchronized (physics) {
                Body body = getBody(Objects.requireNonNull(playerId, "playerId không được null"));
                if (body == null) {
                    throw new IllegalArgumentException("Không có player body: " + playerId);
                }
                var position = body.getPosition();
                return new PhysicsPosition(position.x, position.y);
            }
        }

        /** Quan sát nội bộ physics/test; không public API cho networking. */
        Body getBody(PlayerId playerId) {
            synchronized (physics) {
                if (closed || physics.isDisposed()) {
                    throw new IllegalStateException("Player physics đã đóng");
                }
                return bodies.get(playerId);
            }
        }

        /** Giá trị đọc bất biến để runtime xác định facing; không trả Body/Vector2 mutable. */
        public PhysicsVelocity getVelocity(PlayerId playerId) {
            synchronized (physics) {
                Body body = getBody(Objects.requireNonNull(playerId, "playerId không được null"));
                if (body == null) { throw new IllegalArgumentException("Không có player body: " + playerId); }
                var velocity = body.getLinearVelocity();
                return new PhysicsVelocity(velocity.x, velocity.y);
            }
        }

        @Override
        public void close() {
            synchronized (physics) {
                if (closed) {
                    return;
                }
                if (!physics.isDisposed()) {
                    World world = physics.getWorld();
                    requireUnlocked(world);
                    var iterator = bodies.values().iterator();
                    while (iterator.hasNext()) {
                        world.destroyBody(iterator.next());
                        iterator.remove();
                    }
                }
                bodies.clear();
                physics.releasePlayerPhysics(this);
                closed = true;
            }
        }
    }

    /** Tọa độ native theo mét tại thời điểm đọc; không phải network snapshot. */
    public record PhysicsPosition(float xMeters, float yMeters) { }
    public record PhysicsVelocity(float xMetersPerSecond, float yMetersPerSecond) { }
}
