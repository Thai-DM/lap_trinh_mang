package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Native Box2D thật, manual tick; các fixture bổ sung chỉ nằm trong test tại vùng trống của TMX. */
public class MatchRuntimeJumpTest {
    private static final float EPSILON = 0.00001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test
    public void playerFallsWithManualGravity() {
        Probe probe = create(2);
        float y = probe.body(0).getPosition().y;
        probe.runtime.tick();
        assertTrue(probe.body(0).getPosition().y < y);
        assertTrue(probe.body(0).getLinearVelocity().y < 0.0f);
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
    }

    @Test
    public void gravityMatchesOriginalMechanics() {
        Probe probe = create(2);
        Body body = probe.body(0);
        body.setTransform(8.2f, 3.5f, 0);
        assertEquals(0.0f, probe.world().getGravity().y, 0.0f);
        for (int tick = 1; tick <= 10; tick++) {
            probe.runtime.tick();
            assertEquals(-0.098f * tick / body.getMass(), body.getLinearVelocity().y, EPSILON);
        }
    }

    @Test
    public void groundedPlayerCanJump() {
        Probe probe = landed(2);
        assertTrue(probe.runtime.getJumpState(id(0)).grounded());
        jump(probe, 0, 0);
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
    }

    @Test
    public void jumpMovesPlayerUpward() {
        Probe probe = landed(2);
        float y = probe.body(0).getPosition().y;
        float beforeY = probe.body(0).getLinearVelocity().y;
        float expected = beforeY + (-0.8f * beforeY + 2.2f - 0.098f) / probe.body(0).getMass();
        jump(probe, 0, 0);
        assertTrue(probe.body(0).getPosition().y > y);
        assertEquals(expected, probe.body(0).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void airbornePlayerCanDoubleJump() {
        Probe probe = landed(2);
        jump(probe, 0, 0);
        float before = probe.body(0).getLinearVelocity().y;
        jump(probe, 0, 1);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(before + (-0.8f * before + 2.2f - 0.098f) / probe.body(0).getMass(),
                probe.body(0).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void playerCannotTripleJump() {
        Probe probe = doubleJumped();
        float before = probe.body(0).getLinearVelocity().y;
        jump(probe, 0, 2);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(before - 0.098f / probe.body(0).getMass(), probe.body(0).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void landingResetsJumpAllowance() {
        Probe probe = doubleJumped();
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 120);
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertFalse(probe.runtime.getJumpState(id(0)).extraJumpReady());
    }

    @Test
    public void sideCollisionDoesNotResetJump() {
        Probe probe = doubleJumped();
        tile(probe, 8.35f, 2.0f, 0.05f, 0.5f);
        Body player = probe.body(0);
        player.setTransform(8.245f, 2.0f, 0.0f);
        player.setLinearVelocity(0, 0);
        probe.runtime.tick();
        assertTrue(touchingTile(probe, player));
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        jump(probe, 0, 2);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void ceilingCollisionDoesNotResetJump() {
        Probe probe = doubleJumped();
        tile(probe, 8.2f, 2.0f, 0.2f, 0.1f);
        Body player = probe.body(0);
        player.setTransform(8.2f, 1.845f, 0.0f);
        player.setLinearVelocity(0, 0.5f);
        probe.runtime.tick();
        assertTrue(touchingTile(probe, player));
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void repeatedJumpInputDoesNotTriggerTwice() {
        Probe probe = landed(2);
        PlayerInput press = input(0, 10, 0.0f, true);
        assertEquals(ACCEPTED, probe.runtime.submitInput(press));
        probe.runtime.tick();
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(press));
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 9, 0, true)));
        float before = probe.body(0).getLinearVelocity().y;
        probe.runtime.tick();
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(before - 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void jumpWithoutNewInputDoesNotRepeat() {
        Probe probe = landed(2);
        jump(probe, 0, 0);
        for (int tick = 0; tick < 5; tick++) {
            assertTrue(probe.runtime.tick().isEmpty());
            assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        }
    }

    @Test
    public void playerCanJumpAgainAfterLanding() {
        Probe probe = doubleJumped();
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 120);
        jump(probe, 0, 2);
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertTrue(probe.body(0).getLinearVelocity().y > 0);
    }

    @Test
    public void twoPlayersHaveIndependentJumpState() {
        Probe probe = landed(2);
        jump(probe, 0, 0);
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(0, probe.runtime.getJumpState(id(1)).jumpsUsed());
        assertTrue(probe.runtime.getJumpState(id(1)).grounded());
        jump(probe, 1, 0);
        jump(probe, 0, 1);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(1, probe.runtime.getJumpState(id(1)).jumpsUsed());
    }

    @Test
    public void fourPlayersHaveIndependentJumpState() {
        Probe probe = landed(4);
        for (int slot : new int[]{0, 1, 3}) { send(probe, slot, 0, 0, true); }
        probe.runtime.tick();
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(slot == 2 ? 0 : 1, probe.runtime.getJumpState(id(slot)).jumpsUsed());
        }
        assertTrue(probe.runtime.getJumpState(id(2)).grounded());
        jump(probe, 0, 1);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(1, probe.runtime.getJumpState(id(1)).jumpsUsed());
        assertEquals(0, probe.runtime.getJumpState(id(2)).jumpsUsed());
        assertEquals(1, probe.runtime.getJumpState(id(3)).jumpsUsed());
    }

    @Test
    public void jumpDoesNotChangeHP() {
        Probe probe = doubleJumped();
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 120);
        for (PlayerId playerId : probe.runtime.getMatchState().getPlayerIds()) {
            assertEquals(100.0f, probe.runtime.getMatchState().getPlayer(playerId).getHp(), 0.0f);
            assertFalse(probe.runtime.getMatchState().getPlayer(playerId).isDead());
        }
    }

    @Test
    public void jumpDoesNotBreakHorizontalMovement() {
        Probe probe = landed(2);
        send(probe, 0, 0, 0.5f, true);
        probe.runtime.tick();
        assertEquals(0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertTrue(probe.body(0).getLinearVelocity().y > 0);
        probe.runtime.tick();
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void twoRoomsHaveIndependentGroundState() {
        Probe first = landed(2);
        Probe second = landed(2);
        assertNotSame(first.world(), second.world());
        jump(first, 0, 0);
        assertFalse(first.runtime.getJumpState(id(0)).grounded());
        assertTrue(second.runtime.getJumpState(id(0)).grounded());
        assertEquals(0, second.runtime.getJumpState(id(0)).jumpsUsed());
        first.runtime.close();
        jump(second, 0, 0);
        assertEquals(1, second.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void eachTickStepsWorldExactlyOnce() {
        Probe probe = landed(4);
        int before = probe.steps.get();
        long tickBefore = probe.runtime.getMatchState().getServerTick();
        for (int slot = 0; slot < 4; slot++) { send(probe, slot, 0, 0, true); }
        probe.runtime.tick();
        assertEquals(before + 1, probe.steps.get());
        assertEquals(tickBefore + 1, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void closedRuntimeCannotJump() {
        Probe probe = landed(2);
        send(probe, 0, 0, 0, true);
        int before = probe.steps.get();
        probe.runtime.close();
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(input(0, 1, 0, true)));
        assertThrows(IllegalStateException.class, () -> probe.runtime.getJumpState(id(0)));
        assertEquals(before, probe.steps.get());
        assertTrue(probe.physics.get().isDisposed());
    }

    @Test
    public void previousServerTestsStillPass() {
        Probe probe = landed(2);
        float hp = probe.runtime.getMatchState().getPlayer(id(0)).getHp();
        var state = probe.runtime.getMatchState();
        probe.runtime.start();
        assertSame(state, probe.runtime.getMatchState());
        assertEquals(20, probe.world().getBodyCount());
        assertEquals(20, probe.world().getFixtureCount());
        assertEquals(hp, state.getPlayer(id(0)).getHp(), 0.0f);
    }

    @Test
    public void multipleGroundContactsSurviveEndingOneContact() {
        Probe probe = create(2);
        Body left = tile(probe, 8.05f, 0.9f, 0.15f, 0.1f);
        tile(probe, 8.35f, 0.9f, 0.15f, 0.1f);
        Body player = probe.body(0);
        player.setTransform(8.2f, 1.2f, 0);
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 60);
        int groundContacts = 0;
        for (var contact : probe.world().getContactList()) {
            if (contact.isTouching() && (contact.getFixtureA().getBody() == player
                    || contact.getFixtureB().getBody() == player)) { groundContacts++; }
        }
        assertEquals(2, groundContacts);
        probe.world().destroyBody(left); // Chỉ test kết thúc một contact khi World không locked.
        probe.runtime.tick();
        assertTrue(probe.runtime.getJumpState(id(0)).grounded());
        player.setTransform(8.2f, 2.0f, 0);
        probe.runtime.tick();
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
    }

    @Test
    public void aggregationTriggersOnlyOneJumpPerPlayerPerTick() {
        Probe probe = landed(2);
        send(probe, 0, 0, 0, true);
        send(probe, 0, 1, 0, true);
        send(probe, 0, 2, 0, false);
        List<PlayerInput> batch = probe.runtime.tick();
        assertEquals(1, batch.size());
        assertTrue(batch.get(0).jumpPressed());
        assertEquals(2, batch.get(0).sequence());
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void fallingWithoutInitialGroundJumpCannotUseExtraJump() {
        Probe probe = create(2);
        send(probe, 0, 0, 0, true);
        probe.runtime.tick();
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertTrue(probe.body(0).getLinearVelocity().y < 0);
        // Khớp isExtraJumpReady gốc: đi/rơi khỏi nền mà chưa jump không tự cấp air jump mới.
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 120);
        probe.body(0).setTransform(8.2f, 3, 0);
        probe.runtime.tick();
        jump(probe, 0, 1);
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void jumpStateIsAnImmutableDetachedReadView() {
        Probe probe = landed(2);
        var before = probe.runtime.getJumpState(id(0));
        assertTrue(before.getClass().isRecord());
        jump(probe, 0, 0);
        assertTrue(before.grounded());
        assertEquals(0, before.jumpsUsed());
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertThrows(IllegalArgumentException.class, () -> probe.runtime.getJumpState(id(9)));
    }

    @Test
    public void nonArenaContactCannotResetJumpAllowance() {
        Probe probe = doubleJumped();
        Body object = tile(probe, 8.2f, 0.9f, 0.2f, 0.1f);
        object.setUserData("Projectile-test-only");
        Body player = probe.body(0);
        player.setTransform(8.2f, 1.055f, 0);
        player.setLinearVelocity(0, 0);
        probe.runtime.tick();
        assertTrue(hasTouchingContact(probe, player));
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        // Projectile category gốc = 3 chứa cả bit WORLD=2: phải so sánh category chính xác.
        object.setUserData("Tile");
        var fixture = object.getFixtureList().first();
        var filter = fixture.getFilterData();
        filter.categoryBits = 3;
        fixture.setFilterData(filter);
        probe.runtime.tick();
        assertTrue(hasTouchingContact(probe, player));
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void threePlayersCanLandAndJumpIndependently() {
        Probe probe = landed(3);
        send(probe, 0, 0, 0, true);
        send(probe, 2, 0, 0, true);
        probe.runtime.tick();
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertTrue(probe.runtime.getJumpState(id(1)).grounded());
        assertEquals(0, probe.runtime.getJumpState(id(1)).jumpsUsed());
        assertEquals(1, probe.runtime.getJumpState(id(2)).jumpsUsed());
    }

    @Test(timeout = 15000)
    public void onlySimulationOwnerCanConsumeJumpInput() throws Exception {
        Probe probe = landed(2);
        send(probe, 0, 0, 0, true);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, probe.runtime::tick);
            return true;
        }));
        assertTrue(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
        probe.runtime.tick();
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    private Probe doubleJumped() {
        Probe probe = landed(2);
        jump(probe, 0, 0);
        jump(probe, 0, 1);
        return probe;
    }

    private Probe landed(int count) {
        Probe probe = create(count);
        until(probe, () -> {
            for (int slot = 0; slot < count; slot++) {
                if (!probe.runtime.getJumpState(id(slot)).grounded()) { return false; }
            }
            return true;
        }, 180);
        return probe;
    }

    private static void until(Probe probe, BooleanSupplier condition, int maxTicks) {
        for (int tick = 0; tick < maxTicks && !condition.getAsBoolean(); tick++) { probe.runtime.tick(); }
        assertTrue("Không đạt điều kiện trong " + maxTicks + " manual ticks", condition.getAsBoolean());
    }

    private Probe create(int count) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        String[] characters = {"Alex", "Luca", "Leo", "Viktor"};
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        String matchId = "match-" + runtimes.size();
        MatchSetup setup = new MatchSetup(matchId, "room-" + matchId, participants);
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger();
        MatchRuntime runtime = new MatchRuntime(setup, () -> {
            HeadlessPhysicsWorld created = new PhysicsWorldFactory().create();
            physics.set(created);
            return created;
        }, (world, dt, velocity, position) -> {
            assertEquals(1.0f / 60, dt, 0.0f);
            assertEquals(6, velocity);
            assertEquals(2, position);
            steps.incrementAndGet();
            world.step(dt, velocity, position);
        });
        runtimes.add(runtime);
        runtime.start();
        return new Probe(runtime, physics, steps);
    }

    private static Body tile(Probe probe, float x, float y, float halfWidth, float halfHeight) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.StaticBody;
        definition.position.set(x, y);
        Body body = probe.world().createBody(definition);
        body.setUserData("Tile");
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(halfWidth, halfHeight);
            FixtureDef fixture = new FixtureDef();
            fixture.shape = shape;
            fixture.filter.categoryBits = 2;
            fixture.filter.maskBits = 1 | 3;
            body.createFixture(fixture);
        } finally { shape.dispose(); }
        return body;
    }

    private static boolean touchingTile(Probe probe, Body player) { return hasTouchingContact(probe, player); }

    private static boolean hasTouchingContact(Probe probe, Body player) {
        for (var contact : probe.world().getContactList()) {
            if (contact.isTouching() && (contact.getFixtureA().getBody() == player
                    || contact.getFixtureB().getBody() == player)) { return true; }
        }
        return false;
    }

    private static void jump(Probe probe, int slot, long sequence) {
        send(probe, slot, sequence, 0, true);
        probe.runtime.tick();
    }

    private static void send(Probe probe, int slot, long sequence, float axis, boolean jump) {
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(slot, sequence, axis, jump)));
    }

    private static PlayerInput input(int slot, long sequence, float axis, boolean jump) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, jump, false, false, false, false);
    }

    private static PlayerId id(int slot) { return new PlayerId("arbitrary-player-" + slot); }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps) {
        World world() { return physics.get().getWorld(); }
        Body body(int slot) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (id(slot).equals(body.getUserData())) { return body; }
            }
            throw new AssertionError("Thiếu player body, slot=" + slot);
        }
    }
}
