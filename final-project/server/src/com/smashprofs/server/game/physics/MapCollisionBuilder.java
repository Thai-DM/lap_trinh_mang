package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Port đúng collision của PlayScreen.createTileMap, không tạo geometry hay ContactListener mới.
 * Build/close chỉ được gọi bởi simulation owner, không đồng thời World.step hoặc từ network thread.
 * Khóa wrapper bảo vệ marker/lifecycle; caller vẫn phải tuân thủ single-owner của Box2D.
 */
public final class MapCollisionBuilder {
    // Khớp Player.PPM và B2dContactListener baseline; không import core/rendering vào server.
    public static final float PIXELS_PER_METER = 100.0f;
    static final short WORLD_ENTITY = 0b10;
    static final short PLAYER_ENTITY = 0b01;
    static final short PROJECTILE_ENTITY = 0b11;
    private final BiConsumer<Body, FixtureDef> fixtureCreator;

    public MapCollisionBuilder() {
        this(Body::createFixture);
    }

    /** Seam nhỏ để gây lỗi giữa build trong test; body/fixture vẫn là JNI thật. */
    MapCollisionBuilder(BiConsumer<Body, FixtureDef> fixtureCreator) {
        this.fixtureCreator = Objects.requireNonNull(fixtureCreator, "fixtureCreator không được null");
    }

    public MapCollisionBodies build(HeadlessPhysicsWorld physics, MapCollisionDefinition definition) {
        Objects.requireNonNull(physics, "physics không được null");
        Objects.requireNonNull(definition, "definition không được null");
        // Kiểm tra phép tính float trước JNI; tọa độ âm/bên ngoài map của baseline được giữ nguyên.
        for (MapCollisionDefinition.CollisionRectangle rectangle : definition.rectangles()) {
            if (!Float.isFinite((rectangle.xPixels() + rectangle.widthPixels() / 2) / PIXELS_PER_METER)
                    || !Float.isFinite((rectangle.yPixels() + rectangle.heightPixels() / 2) / PIXELS_PER_METER)) {
                throw new IllegalArgumentException("Body center không hữu hạn, ID=" + rectangle.objectId());
            }
        }
        synchronized (physics) {
            World world = physics.getWorld();
            requireUnlocked(world);
            MapCollisionBodies collision = new MapCollisionBodies(physics);
            physics.claimMapCollision(collision);
            PolygonShape shape = null;
            try {
                shape = new PolygonShape();
                FixtureDef fixture = new FixtureDef();
                fixture.filter.categoryBits = WORLD_ENTITY;
                fixture.filter.maskBits = PLAYER_ENTITY | PROJECTILE_ENTITY;
                // Giữ defaults của FixtureDef gốc: density=0, friction=0.2, restitution=0, sensor=false, group=0.
                fixture.shape = shape;
                for (MapCollisionDefinition.CollisionRectangle rectangle : definition.rectangles()) {
                    BodyDef bodyDefinition = new BodyDef();
                    bodyDefinition.type = BodyDef.BodyType.StaticBody;
                    bodyDefinition.position.set(
                            (rectangle.xPixels() + rectangle.widthPixels() / 2) / PIXELS_PER_METER,
                            (rectangle.yPixels() + rectangle.heightPixels() / 2) / PIXELS_PER_METER);
                    Body body = world.createBody(bodyDefinition);
                    collision.bodies.add(body); // Ghi nhận trước bước fixture để rollback cả body vừa tạo.
                    body.setUserData("Tile");
                    shape.setAsBox(rectangle.widthPixels() / 2 / PIXELS_PER_METER,
                            rectangle.heightPixels() / 2 / PIXELS_PER_METER);
                    fixtureCreator.accept(body, fixture);
                }
                return collision;
            } catch (RuntimeException | Error failure) {
                try {
                    collision.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            } finally {
                if (shape != null) {
                    shape.dispose(); // Fixture đã copy shape; không giữ PolygonShape tạm.
                }
            }
        }
    }

    private static void requireUnlocked(World world) {
        if (world.isLocked()) {
            throw new IllegalStateException("Không build/destroy map bodies trong World.step");
        }
    }

    /** Sở hữu đúng các bodies của một build, không sở hữu hoặc dispose World của caller. */
    public static final class MapCollisionBodies implements AutoCloseable {
        private final HeadlessPhysicsWorld physics;
        private final List<Body> bodies = new ArrayList<>();
        private boolean closed;

        private MapCollisionBodies(HeadlessPhysicsWorld physics) {
            this.physics = physics;
        }

        public int getBodyCount() {
            synchronized (physics) {
                return closed || physics.isDisposed() ? 0 : bodies.size();
            }
        }

        public boolean isClosed() {
            synchronized (physics) {
                return closed || physics.isDisposed();
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
                    while (!bodies.isEmpty()) {
                        int last = bodies.size() - 1;
                        world.destroyBody(bodies.get(last));
                        bodies.remove(last);
                    }
                }
                // Nếu caller đã dispose World, native bodies cũng đã giải phóng: không JNI destroy lần hai.
                bodies.clear();
                physics.releaseMapCollision(this);
                closed = true;
            }
        }
    }
}
