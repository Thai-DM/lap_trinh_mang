package com.smashprofs.server.game.projectile;

import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchState;
import com.smashprofs.server.game.ServerPlayer;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PlayerPhysicsFactory.PlayerPhysicsBodies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Một manager mỗi runtime; chỉ simulation owner gọi, callback chỉ ghi ID, không destroy/damage. */
public final class ProjectileManager implements ContactListener, AutoCloseable {
    private static final short PROJECTILE_ENTITY = 0b11; // B2dContactListener.PROJECTILE_ENTITY gốc.
    // IBM.java/Projectile.java/ShapeCreator.java gốc. Không lấy delay static dùng chung mọi loại projectile.
    public static final int IBM_COOLDOWN_TICKS = 3 * 60;
    public static final float IBM_RADIUS_METERS = 3.0f / 100.0f;
    public static final float IBM_SPAWN_OFFSET_METERS = 15.0f / 100.0f;
    public static final float IBM_SPEED = 0.7f;
    public static final float IBM_ANGULAR_SPEED = 4.0f;
    public static final float IBM_DAMAGE = 10.0f;
    // ThrowingStar gốc: một skill tạo bốn hướng, constructor khai báo 15 nhưng contact path thực tế trừ 10.
    public static final int STAR_COOLDOWN_TICKS = 45; // 0.75 s * 60 Hz, không dùng delay static chung.
    public static final float STAR_RADIUS_METERS = 3.0f / 100.0f;
    public static final float STAR_SPAWN_OFFSET_METERS = 10.0f / 100.0f;
    public static final float STAR_SPEED = 1.0f;
    public static final float STAR_ANGULAR_SPEED = -12.0f;
    public static final float STAR_DAMAGE = 10.0f;
    // Landmine/GameObject gốc: impulse gravity mỗi update, không velocity ban đầu hoặc lifetime.
    public static final int MINE_COOLDOWN_TICKS = 5 * 60;
    public static final float MINE_RADIUS_METERS = 5.0f / 100.0f;
    public static final float MINE_SPAWN_OFFSET_METERS = 15.0f / 100.0f;
    public static final float MINE_GRAVITY_IMPULSE = -0.098f;
    // Constructor khai báo 25, nhưng CombatManager/contact flags thực tế dùng attackDamage=10.
    public static final float MINE_DAMAGE = 10.0f;
    // HomingMissile gốc: field speed=0.7 không được dùng; update thực tế nor().scl(0.5).
    public static final int ROCKET_COOLDOWN_TICKS = 3 * 60;
    public static final float ROCKET_RADIUS_METERS = 3.0f / 100.0f;
    public static final float ROCKET_SPAWN_OFFSET_METERS = 15.0f / 100.0f;
    public static final float ROCKET_SPEED = 0.5f;
    public static final float ROCKET_INERTIA = 0.05f;
    public static final float ROCKET_DAMAGE = 10.0f;
    public static final float KNOCKBACK_X = 1.5f;
    public static final float KNOCKBACK_Y = 2.0f;
    private final HeadlessPhysicsWorld physics;
    private final MatchState state;
    private final PlayerPhysicsBodies players;
    private final Map<Long, ServerProjectile> active = new LinkedHashMap<>();
    private final Map<AbilityKey, Long> nextAllowedTick = new HashMap<>();
    private final Set<Long> collided = new HashSet<>();
    private final Map<Long, Set<PlayerId>> contactTargets = new HashMap<>();
    private final List<PendingHit> pendingHits = new ArrayList<>();
    private long nextProjectileId = 1L;
    private boolean closed;
    private boolean gameplayLimits;
    public interface BossTarget {
        float x();float y();
        boolean hit(PlayerId owner,float x,float y,float radius,float damage);
    }
    private BossTarget bossTarget;
    public void setBossTarget(BossTarget target) { bossTarget=Objects.requireNonNull(target); }
    public void enableGameplayLimits() { gameplayLimits=true; }
    public void expireGameplayProjectiles(long tick) {
        if(!gameplayLimits) { return; }
        var world=requireWorld();var iterator=active.values().iterator();
        while(iterator.hasNext()) {
            var p=iterator.next();
            if(tick-p.getSpawnTick()>=600 || state.getPlayer(p.getOwnerPlayerId()).isDead()) {
                world.destroyBody(p.body());p.releaseBody();iterator.remove();collided.remove(p.getProjectileId());contactTargets.remove(p.getProjectileId());
            }
        }
    }

    public ProjectileManager(HeadlessPhysicsWorld physics, MatchState state, PlayerPhysicsBodies players) {
        this.physics = Objects.requireNonNull(physics, "physics không được null");
        this.state = Objects.requireNonNull(state, "state không được null");
        this.players = Objects.requireNonNull(players, "players không được null");
        requireWorld();
    }

    /** Runtime chỉ gọi từ ability edge đã qua queue. Không có target/position/damage do client cung cấp. */
    public boolean spawnProjectile(PlayerId ownerId, long serverTick) {
        return spawnProjectile(ownerId, 0, 0, serverTick);
    }
    public boolean spawnProjectile(PlayerId ownerId, float aimX, float aimY, long serverTick) {
        World world = requireWorld();
        if (serverTick < 0) { throw new IllegalArgumentException("serverTick không được âm"); }
        ServerPlayer owner = ownerId == null ? null : state.getPlayer(ownerId);
        if (owner == null || owner.isDead()) { return false; }
        ProjectileType type = ProjectileType.forCharacter(owner.getCharacterId());
        if (type == null) { return false; }
        if(gameplayLimits && active.size()+(type==ProjectileType.STAR?4:1)>64) { return false; }
        AbilityKey ability = new AbilityKey(ownerId, type);
        if (serverTick < nextAllowedTick.getOrDefault(ability, 0L)) { return false; }
        var origin = players.getPosition(ownerId);
        PlayerId rocketTarget = type == ProjectileType.ROCKET ? (bossTarget==null?selectRocketTarget(ownerId):ownerId) : null;
        // Không có đối thủ sống: không spawn, không tiêu cooldown, không fallback sang skill khác.
        if (type == ProjectileType.ROCKET && rocketTarget == null) { return false; }

        boolean hasAim = aimX != 0 || aimY != 0;
        float facing = owner.isFacingRight() ? 1.0f : -1.0f;
        float dirX = hasAim ? aimX : facing, dirY = hasAim ? aimY : 0;
        double len = Math.hypot(dirX, dirY);
        float ux = (float)(dirX / len), uy = (float)(dirY / len);
        float aimAngle = (float)Math.atan2(uy, ux);

        List<ServerProjectile> created = new ArrayList<>(type == ProjectileType.STAR ? 4 : 1);
        try {
            switch (type) {
                case IBM -> {
                    if (!hasAim) {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                origin.xMeters() + facing * IBM_SPAWN_OFFSET_METERS, origin.yMeters(),
                                facing * IBM_SPEED, 0));
                    } else {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                origin.xMeters() + ux * IBM_SPAWN_OFFSET_METERS, origin.yMeters() + uy * IBM_SPAWN_OFFSET_METERS,
                                ux * IBM_SPEED, uy * IBM_SPEED));
                    }
                }
                case STAR -> {
                    float x = origin.xMeters();
                    float y = origin.yMeters();
                    if (!hasAim) {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                x + STAR_SPAWN_OFFSET_METERS, y, STAR_SPEED, 0));
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                x - STAR_SPAWN_OFFSET_METERS, y, -STAR_SPEED, 0));
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                x, y + STAR_SPAWN_OFFSET_METERS, 0, STAR_SPEED));
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                x, y - STAR_SPAWN_OFFSET_METERS, 0, -STAR_SPEED));
                    } else {
                        for(int i = 0; i < 4; i++) {
                            float a = aimAngle + i * (float)(Math.PI / 2);
                            float sx = STAR_SPEED * (float)Math.cos(a), sy = STAR_SPEED * (float)Math.sin(a);
                            created.add(createProjectile(world, type, ownerId, serverTick,
                                    x + (float)Math.cos(a) * STAR_SPAWN_OFFSET_METERS, y + (float)Math.sin(a) * STAR_SPAWN_OFFSET_METERS,
                                    sx, sy));
                        }
                    }
                }
                case MINE -> {
                    if (!hasAim) {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                origin.xMeters() + facing * MINE_SPAWN_OFFSET_METERS, origin.yMeters(), 0, 0));
                    } else {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                origin.xMeters() + ux * MINE_SPAWN_OFFSET_METERS, origin.yMeters() + uy * MINE_SPAWN_OFFSET_METERS,
                                ux * 2.0f, Math.max(0, uy * 3.0f)));
                    }
                }
                case ROCKET -> {
                    if (!hasAim) {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                origin.xMeters() + facing * ROCKET_SPAWN_OFFSET_METERS, origin.yMeters(),
                                0, 0, rocketTarget));
                    } else {
                        created.add(createProjectile(world, type, ownerId, serverTick,
                                origin.xMeters() + ux * ROCKET_SPAWN_OFFSET_METERS, origin.yMeters() + uy * ROCKET_SPAWN_OFFSET_METERS,
                                ux * 2.0f, uy * 2.0f, rocketTarget));
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            // Burst không publish một phần hoặc tiêu cooldown nếu tạo fixture/body thất bại.
            for (ServerProjectile projectile : created) {
                try { world.destroyBody(projectile.body()); }
                catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                finally { projectile.releaseBody(); }
            }
            throw failure;
        }
        for (ServerProjectile projectile : created) { active.put(projectile.getProjectileId(), projectile); }
        nextAllowedTick.put(ability, serverTick + cooldownTicks(type));
        return true;
    }

    /** Policy multiplayer đã chốt: gần owner nhất lúc bắn, tie theo PlayerId, không phụ thuộc slot. */
    private PlayerId selectRocketTarget(PlayerId ownerId) {
        var origin = players.getPosition(ownerId);
        return state.getPlayerIds().stream().map(state::getPlayer)
                .filter(player -> !player.getPlayerId().equals(ownerId) && !player.isDead())
                .min(Comparator.<ServerPlayer>comparingDouble(player -> {
                    var position = players.getPosition(player.getPlayerId());
                    double dx = position.xMeters() - origin.xMeters();
                    double dy = position.yMeters() - origin.yMeters();
                    return dx * dx + dy * dy;
                }).thenComparing(player -> player.getPlayerId().value()))
                .map(ServerPlayer::getPlayerId).orElse(null);
    }

    /** Chung native creation theo source; không thêm backend render, mỗi type có profile riêng. */
    private ServerProjectile createProjectile(World world, ProjectileType type, PlayerId ownerId, long serverTick,
                                             float x, float y, float velocityX, float velocityY) {
        return createProjectile(world, type, ownerId, serverTick, x, y, velocityX, velocityY, null);
    }

    private ServerProjectile createProjectile(World world, ProjectileType type, PlayerId ownerId, long serverTick,
                                             float x, float y, float velocityX, float velocityY, PlayerId targetId) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.DynamicBody;
        definition.fixedRotation = false;
        definition.position.set(x, y);
        Body body = world.createBody(definition);
        CircleShape shape = null;
        try {
            shape = new CircleShape();
            shape.setRadius(switch (type) {
                case IBM -> IBM_RADIUS_METERS;
                case STAR -> STAR_RADIUS_METERS;
                case MINE -> MINE_RADIUS_METERS;
                case ROCKET -> ROCKET_RADIUS_METERS;
            });
            FixtureDef fixture = new FixtureDef();
            fixture.shape = shape;
            fixture.filter.categoryBits = PROJECTILE_ENTITY;
            // Giữ defaults: mask=0xffff, group=0, density=0, sensor=false, friction=0.2, restitution=0.
            body.createFixture(fixture);
            body.setAngularVelocity(switch (type) {
                case IBM -> IBM_ANGULAR_SPEED;
                case STAR -> STAR_ANGULAR_SPEED;
                case MINE -> 0.0f; // Landmine không đặt angular velocity, Box2D xử lý va chạm tự nhiên.
                case ROCKET -> 0.0f; // HomingMissile không đặt angular velocity; sprite rotation không port.
            });
            ServerProjectile projectile = new ServerProjectile(nextProjectileId++, state.getMatchId(), ownerId,
                    serverTick, type, velocityX, velocityY, body, targetId);
            body.setUserData(projectile);
            return projectile;
        } catch (RuntimeException | Error failure) {
            world.destroyBody(body);
            throw failure;
        } finally {
            if (shape != null) { shape.dispose(); }
        }
    }

    private static int cooldownTicks(ProjectileType type) {
        return switch (type) {
            case IBM -> IBM_COOLDOWN_TICKS;
            case STAR -> STAR_COOLDOWN_TICKS;
            case MINE -> MINE_COOLDOWN_TICKS;
            case ROCKET -> ROCKET_COOLDOWN_TICKS;
        };
    }

    private static float damage(ProjectileType type) {
        return switch (type) {
            case IBM -> IBM_DAMAGE;
            case STAR -> STAR_DAMAGE;
            case MINE -> MINE_DAMAGE;
            case ROCKET -> ROCKET_DAMAGE;
        };
    }

    /** CombatManager gốc update projectiles TRƯỚC shooting: body mới đứng yên ở spawn tick. */
    public void updateProjectiles() {
        requireWorld();
        for (ServerProjectile projectile : active.values()) {
            Body body = projectile.body();
            if (projectile.type() == ProjectileType.MINE) {
                // Impulse cộng vào vận tốc hiện có; không ghi đè response va chạm hoặc gravity đã tích lũy.
                var center = body.getWorldCenter();
                body.applyLinearImpulse(0, MINE_GRAVITY_IMPULSE, center.x, center.y, true);
            } else if (projectile.type() == ProjectileType.ROCKET) {
                // Target ID bất biến trong roster; Body giữ đến match close, kể cả target đã chết.
                // Không retarget hoặc giữ reference Body/Player của target. Đây là policy người dùng đã chốt.
                var target = players.getPosition(projectile.getTargetPlayerId());
                projectile.updateHoming(bossTarget==null?target.xMeters():bossTarget.x(), bossTarget==null?target.yMeters():bossTarget.y(), ROCKET_INERTIA, ROCKET_SPEED);
            } else {
                body.setLinearVelocity(projectile.velocityX(), projectile.velocityY());
            }
        }
    }

    /** Callback xử lý cả thứ tự fixture A/B; chỉ nhận body do chính manager này tạo. */
    @Override public void beginContact(Contact contact) {
        if (!closed && contact.isTouching() && contact.isEnabled()) {
            record(contact.getFixtureA().getBody(), contact.getFixtureB().getBody());
            record(contact.getFixtureB().getBody(), contact.getFixtureA().getBody());
        }
    }

    private void record(Body body, Body other) {
        if (!(body.getUserData() instanceof ServerProjectile projectile)
                || active.get(projectile.getProjectileId()) != projectile || projectile.body() != body) { return; }
        if(bossTarget!=null && other.getUserData() instanceof PlayerId) { return; }
        // Mine giữ nguyên native contact với nền: vẫn rơi/đứng trên Tile, không nổ hoặc gây AoE.
        if (projectile.type() == ProjectileType.MINE && "Tile".equals(other.getUserData())) { return; }
        long id = projectile.getProjectileId();
        collided.add(id); // IBM/Star/Rocket: mọi contact; Mine: chỉ contact không phải Tile.
        if (other.getUserData() instanceof PlayerId targetId && state.containsPlayer(targetId)) {
            // Source không có owner exclusion: self-hit được giữ, không tự thêm immunity/team mode.
            contactTargets.computeIfAbsent(id, key -> new HashSet<>()).add(targetId);
        }
    }

    @Override public void endContact(Contact contact) { }
    @Override public void preSolve(Contact contact, Manifold oldManifold) {
        if(bossTarget!=null) {
            var a=contact.getFixtureA().getBody().getUserData();var b=contact.getFixtureB().getBody().getUserData();
            if(a instanceof ServerProjectile && b instanceof PlayerId || b instanceof ServerProjectile && a instanceof PlayerId) { contact.setEnabled(false); }
        }
    }
    @Override public void postSolve(Contact contact, ContactImpulse impulse) { }

    /** Sau step: một target contact gần nhất; Rocket tie theo PlayerId, các type cũ giữ policy slot. */
    public void collectHitsAfterStep() {
        requireWorld();
        if(bossTarget!=null) {
            for(var p:active.values()) {
                var v=p.view();
                if(bossTarget.hit(p.getOwnerPlayerId(),v.x(),v.y(),.06f,damage(p.type()))) { collided.add(p.getProjectileId()); }
            }
        }
        for (long id : collided.stream().sorted().toList()) {
            ServerProjectile projectile = active.get(id);
            if (projectile == null) { continue; }
            float x = projectile.body().getPosition().x;
            float y = projectile.body().getPosition().y;
            ServerPlayer target = contactTargets.getOrDefault(id, Set.of()).stream().map(state::getPlayer)
                    .filter(player -> !player.isDead())
                    .min(Comparator.<ServerPlayer>comparingDouble(player -> {
                        var position = players.getPosition(player.getPlayerId());
                        double dx = position.xMeters() - x;
                        double dy = position.yMeters() - y;
                        return dx * dx + dy * dy;
                    }).thenComparing(projectile.type() == ProjectileType.ROCKET
                            ? Comparator.comparing(player -> player.getPlayerId().value())
                            : Comparator.comparingInt(ServerPlayer::getSlotIndex))).orElse(null);
            if (target != null) {
                pendingHits.add(new PendingHit(id, projectile.getOwnerPlayerId(), target.getPlayerId(), projectile.type()));
            }
        }
    }

    /** Combat tick kế tiếp như contact flags gốc; không giữ Body/Contact đã bị destroy trong event. */
    public List<ProjectileHit> resolveHits() {
        requireWorld();
        List<ProjectileHit> hits = new ArrayList<>();
        for (PendingHit pending : pendingHits) {
            ServerPlayer target = state.getPlayer(pending.targetId());
            if (target == null || target.isDead()) { continue; }
            float ownerX = players.getPosition(pending.ownerId()).xMeters();
            float targetX = players.getPosition(pending.targetId()).xMeters();
            // CombatManager gốc dùng origin của owner, không block check; cùng X đẩy trái.
            hits.add(new ProjectileHit(pending.projectileId(), pending.ownerId(), pending.targetId(), damage(pending.type()),
                    ownerX < targetX ? KNOCKBACK_X : -KNOCKBACK_X, KNOCKBACK_Y));
        }
        pendingHits.clear();
        return List.copyOf(hits);
    }

    /** Chỉ sau World.step: destroy đúng một lần, xóa handle ngay, không gọi từ listener. */
    public void cleanupDestroyed() {
        World world = requireWorld();
        for (long id : collided.stream().sorted().toList()) {
            ServerProjectile projectile = active.get(id);
            if (projectile != null) {
                world.destroyBody(projectile.body());
                projectile.releaseBody();
                active.remove(id);
            }
        }
        collided.clear();
        contactTargets.clear();
    }

    public int getActiveCount() { return active.size(); }

    public List<ServerProjectile.View> getProjectiles() {
        requireWorld();
        return active.values().stream().map(ServerProjectile::view).toList();
    }

    private World requireWorld() {
        if (closed) { throw new IllegalStateException("Projectile manager đã đóng"); }
        World world = physics.getWorld();
        if (world.isLocked()) { throw new IllegalStateException("Không mutate projectile trong World.step"); }
        return world;
    }

    @Override public void close() {
        if (closed) { return; }
        if (!physics.isDisposed()) {
            World world = requireWorld();
            for (ServerProjectile projectile : active.values()) { world.destroyBody(projectile.body()); }
        }
        for (ServerProjectile projectile : active.values()) { projectile.releaseBody(); }
        active.clear();
        collided.clear();
        contactTargets.clear();
        pendingHits.clear();
        nextAllowedTick.clear();
        closed = true;
    }

    private record AbilityKey(PlayerId ownerId, ProjectileType type) { }
    private record PendingHit(long projectileId, PlayerId ownerId, PlayerId targetId, ProjectileType type) { }
    public record ProjectileHit(long projectileId, PlayerId ownerId, PlayerId targetId,
                                float damage, float knockbackX, float knockbackY) { }
}
