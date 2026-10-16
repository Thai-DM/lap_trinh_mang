package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.smashprofs.common.model.PlayerId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Listener riêng từng World: chỉ ghi nhận ground contacts, không mutate Body trong callback. */
public final class GroundContactListener implements ContactListener, AutoCloseable {
    // Ngưỡng phân loại hướng đỡ từ dưới, không phải gravity/jump force hoặc thông số balance.
    private static final float MIN_SUPPORT_NORMAL_Y = 0.5f;
    private final PlayerPhysicsFactory.PlayerPhysicsBodies players;
    private final Map<PlayerId, Set<GroundContact>> supports = new HashMap<>();
    private final Map<PlayerId,Map<GroundContact,Integer>> walls=new HashMap<>();

    public GroundContactListener(PlayerPhysicsFactory.PlayerPhysicsBodies players) {
        this.players = Objects.requireNonNull(players, "players không được null");
        for (PlayerId playerId : players.getPlayerIds()) {
            supports.put(playerId, new HashSet<>());
            walls.put(playerId,new HashMap<>());
        }
    }

    @Override
    public void beginContact(Contact contact) { update(contact); }

    @Override
    public void preSolve(Contact contact, Manifold oldManifold) {
        // Một contact có thể chuyển từ mặt trên sang cạnh: không chỉ dựa vào beginContact.
        update(contact);
    }

    @Override
    public void endContact(Contact contact) { remove(contact.getFixtureA(), contact.getFixtureB()); }

    @Override
    public void postSolve(Contact contact, ContactImpulse impulse) { }

    /** Simulation owner đọc sau step; end một contact không xóa các contact hỗ trợ còn lại. */
    public boolean hasGroundContact(PlayerId playerId) {
        Set<GroundContact> contacts = supports.get(playerId);
        return contacts != null && !contacts.isEmpty();
    }
    public int wallDirection(PlayerId id) { var values=walls.get(id);return values==null || values.isEmpty()?0:values.values().iterator().next(); }
    public void forget(PlayerId id) { supports.get(id).clear();walls.get(id).clear(); }

    int getGroundContactCount(PlayerId playerId) {
        Set<GroundContact> contacts = supports.get(playerId);
        return contacts == null ? 0 : contacts.size();
    }

    private void update(Contact contact) {
        Fixture a = contact.getFixtureA();
        Fixture b = contact.getFixtureB();
        remove(a, b);
        if (!contact.isTouching() || !contact.isEnabled() || a.isSensor() || b.isSensor()) {
            return;
        }
        recordSupport(contact, a, b, -1.0f); // Normal Box2D hướng từ A sang B.
        recordSupport(contact, b, a, 1.0f);
    }

    private void recordSupport(Contact contact, Fixture playerFixture, Fixture groundFixture, float normalSign) {
        Body playerBody = playerFixture.getBody();
        Body groundBody = groundFixture.getBody();
        if (!(playerBody.getUserData() instanceof PlayerId playerId) || !supports.containsKey(playerId)
                || players.getBody(playerId) != playerBody
                || playerFixture.getFilterData().categoryBits != MapCollisionBuilder.PLAYER_ENTITY
                || groundBody.getType() != BodyDef.BodyType.StaticBody
                || !"Tile".equals(groundBody.getUserData())
                || groundFixture.getFilterData().categoryBits != MapCollisionBuilder.WORLD_ENTITY) {
            return;
        }
        var manifold = contact.getWorldManifold();
        float nx=normalSign*manifold.getNormal().x;
        if(Math.abs(nx)>.7f) { walls.get(playerId).put(new GroundContact(playerFixture,groundFixture),nx>0?1:-1); }
        if (normalSign * manifold.getNormal().y < MIN_SUPPORT_NORMAL_Y) {
            return;
        }
        for (int point = 0; point < manifold.getNumberOfContactPoints(); point++) {
            if (manifold.getPoints()[point].y < playerBody.getPosition().y) {
                supports.get(playerId).add(new GroundContact(playerFixture, groundFixture));
                return;
            }
        }
    }

    private void remove(Fixture a, Fixture b) {
        // Tối đa 4 player; không lưu Contact wrapper tái sử dụng bởi JNI.
        GroundContact forward = new GroundContact(a, b);
        GroundContact reverse = new GroundContact(b, a);
        for (Set<GroundContact> contacts : supports.values()) {
            contacts.remove(forward);
            contacts.remove(reverse);
        }
        for(var contacts:walls.values()) { contacts.remove(forward);contacts.remove(reverse); }
    }

    @Override
    public void close() {
        supports.clear(); // Chỉ bỏ references, an toàn cả sau World.dispose.
        walls.clear();
    }

    private record GroundContact(Fixture playerFixture, Fixture groundFixture) { }
}
