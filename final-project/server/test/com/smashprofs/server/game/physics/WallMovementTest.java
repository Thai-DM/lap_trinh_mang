package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchSetup;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class WallMovementTest {
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
