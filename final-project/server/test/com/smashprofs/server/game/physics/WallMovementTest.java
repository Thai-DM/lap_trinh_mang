package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchSetup;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class WallMovementTest {
    @Test public void risingIntoEitherWallLatchesAndJumpUsesSmallOutwardSpeed() {
        for(int side:new int[]{-1,1}) {
            try(var f=new WallFixture(side)) {
                f.body.setLinearVelocity(-side*.5f,1);f.step();f.jumps.setMovementIntent(f.id,-side);
                f.jumps.applyGravity();assertEquals("WALL_SLIDING",f.jumps.getWallState(f.id));
                assertEquals(0,f.body.getLinearVelocity().x,.0001f);assertTrue(f.body.getLinearVelocity().y>0);
                assertTrue(f.jumps.jump(f.id));assertEquals(side*.6f,f.body.getLinearVelocity().x,.0001f);
                assertEquals(2.4f,f.body.getLinearVelocity().y,.0001f);
                assertTrue(f.jumps.jump(f.id));assertFalse(f.jumps.jump(f.id));
            }
        }
    }
    @Test public void briefContactLossKeepsWallJumpWindowButNeverCancelsCombatKnockback() {
        try(var f=new WallFixture(1)) {
            f.body.setLinearVelocity(-.5f,0);f.step();f.body.setTransform(6.3f,2.5f,0);f.step();
            assertTrue(f.jumps.jump(f.id));assertEquals(.6f,f.body.getLinearVelocity().x,.0001f);
        }
        try(var f=new WallFixture(1)) {
            f.body.setLinearVelocity(-.5f,0);f.step();f.jumps.setMovementIntent(f.id,-1);
            f.jumps.applyProjectileKnockback(f.id,1,2);float vx=f.body.getLinearVelocity().x;f.jumps.applyGravity();
            assertEquals(vx,f.body.getLinearVelocity().x,0);assertNotEquals("WALL_SLIDING",f.jumps.getWallState(f.id));
        }
    }
    private static final class WallFixture implements AutoCloseable {
        final HeadlessPhysicsWorld physics=new PhysicsWorldFactory().create();
        final PlayerId id=new PlayerId("p0");
        final MapCollisionBuilder.MapCollisionBodies arena;
        final PlayerPhysicsFactory.PlayerPhysicsBodies players;
        final PlayerJumpController jumps;final Body body;
        WallFixture(int side) {
            var geometry=new TmxCollisionLoader().loadOriginalMap();arena=new MapCollisionBuilder().build(physics,geometry);
            var setup=new MatchSetup("wall","room",List.of(new MatchSetup.Participant(id,0,"Alex"),new MatchSetup.Participant(new PlayerId("p1"),1,"Leo")));
            players=new PlayerPhysicsFactory().build(physics,setup,geometry);jumps=new PlayerJumpController(players);jumps.enableWallMovement();
            var definition=new BodyDef();definition.position.set(6,2.5f);var wall=physics.getWorld().createBody(definition);wall.setUserData("Tile");
            var shape=new PolygonShape();shape.setAsBox(.05f,1);
            try { var fixture=new FixtureDef();fixture.shape=shape;fixture.filter.categoryBits=MapCollisionBuilder.WORLD_ENTITY;wall.createFixture(fixture); } finally { shape.dispose(); }
            body=players.getBody(id);body.setTransform(6+side*.101f,2.5f,0);
        }
        void step() { physics.getWorld().step(1/60f,6,2);jumps.synchronizeAfterStep(); }
        @Override public void close() { jumps.close();players.close();arena.close();physics.close(); }
    }
    @Test public void fallingAgainstEitherWallSlidesAndJumpsAwayWithoutInfiniteAirJumps() {
        for(int direction:new int[]{-1,1}) {
            try(var physics=new PhysicsWorldFactory().create()) {
                var id=new PlayerId("p0");var setup=new MatchSetup("wall","room",List.of(new MatchSetup.Participant(id,0,"Alex"),new MatchSetup.Participant(new PlayerId("p1"),1,"Leo")));
                var geometry=new TmxCollisionLoader().loadOriginalMap();
                try(var arena=new MapCollisionBuilder().build(physics,geometry);var players=new PlayerPhysicsFactory().build(physics,setup,geometry);var jumps=new PlayerJumpController(players)) {
                    jumps.enableWallMovement();var world=physics.getWorld();
                    var definition=new BodyDef();definition.position.set(6,2.5f);var wall=world.createBody(definition);wall.setUserData("Tile");
                    var shape=new PolygonShape();shape.setAsBox(.05f,1);
                    try { var fixture=new FixtureDef();fixture.shape=shape;fixture.filter.categoryBits=MapCollisionBuilder.WORLD_ENTITY;wall.createFixture(fixture); } finally { shape.dispose(); }
                    var body=players.getBody(id);body.setTransform(6+direction*.101f,2.5f,0);body.setLinearVelocity(-direction*.5f,-1);
                    world.step(1/60f,6,2);jumps.synchronizeAfterStep();jumps.applyGravity();
                    assertEquals("WALL_SLIDING",jumps.getWallState(id));assertTrue(body.getLinearVelocity().y>=-.7f);
                    assertTrue(jumps.jump(id));assertTrue(direction*body.getLinearVelocity().x>0);assertTrue(body.getLinearVelocity().y>0);assertEquals("WALL_JUMPING",jumps.getWallState(id));
                    body.setTransform(8,2.5f,0);world.step(1/60f,6,2);jumps.synchronizeAfterStep();
                    jumps.jump(id);assertFalse(jumps.jump(id));
                    body.setTransform(6+direction*.101f,2.5f,0);body.setLinearVelocity(-direction*.5f,-1);
                    world.step(1/60f,6,2);jumps.synchronizeAfterStep();jumps.applyProjectileKnockback(id,direction,2);body.setLinearVelocity(body.getLinearVelocity().x,-2);jumps.applyGravity();
                    assertNotEquals("WALL_SLIDING",jumps.getWallState(id));assertTrue(body.getLinearVelocity().y<-.7f);
                }
            }
        }
    }
}
