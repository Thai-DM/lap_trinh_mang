package com.smashprofs.server.game.physics;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.common.model.PlayerId;
import java.util.*;

/** All native operations remain on MatchRuntime's owner. Sensors form a capsule hurtbox. */
public final class FighterPhysics implements AutoCloseable {
    private final World world;
    private final PlayerPhysicsFactory.PlayerPhysicsBodies players;
    private final PlayerJumpController jumps;
    private final Map<PlayerId,Integer> dropTicks=new HashMap<>();
    private final List<MapCollisionDefinition.CollisionRectangle> platforms;
    public record Hurtbox(PlayerId id) { }
    public FighterPhysics(World world,PlayerPhysicsFactory.PlayerPhysicsBodies players,PlayerJumpController jumps,MapCollisionDefinition geometry) {
        this.world=world;this.players=players;this.jumps=jumps;
        platforms=geometry.rectangles().stream().filter(FighterPhysics::oneWay).toList();
        for(var id:players.getPlayerIds()) {
            var body=players.getBody(id);var box=new PolygonShape();var circle=new CircleShape();
            try {
                var def=new FixtureDef();def.isSensor=true;def.filter.categoryBits=16;def.filter.maskBits=0;
                box.setAsBox(.085f,.09f,new Vector2(0,.08f),0);def.shape=box;body.createFixture(def).setUserData(new Hurtbox(id));
                circle.setRadius(.085f);def.shape=circle;
                for(float y:new float[]{-.01f,.17f}) { circle.setPosition(new Vector2(0,y));body.createFixture(def).setUserData(new Hurtbox(id)); }
            } finally { box.dispose();circle.dispose(); }
        }
        jumps.setContactPolicy(this::filter);
    }
    public static boolean oneWay(MapCollisionDefinition.CollisionRectangle r) {
        return r.heightPixels()>0 && r.heightPixels()<=40 && r.widthPixels()>=30 && (r.yPixels()+r.heightPixels())/100>.65f;
    }
    private void filter(Contact contact) {
        Fixture a=contact.getFixtureA(),b=contact.getFixtureB();
        if(a.getBody().getUserData() instanceof PlayerId id) { filter(contact,id,b); }
        if(b.getBody().getUserData() instanceof PlayerId id) { filter(contact,id,a); }
    }
    private void filter(Contact contact,PlayerId id,Fixture floor) {
        if(!(floor.getUserData() instanceof MapCollisionDefinition.CollisionRectangle r) || !oneWay(r)) { return; }
        var body=players.getBody(id);float top=(r.yPixels()+r.heightPixels())/100;
        if(dropTicks.getOrDefault(id,0)>0 || body.getLinearVelocity().y>0 || body.getPosition().y-.05f<top-.025f) { contact.setEnabled(false); }
    }
    public void tick() { dropTicks.replaceAll((id,ticks)->Math.max(0,ticks-1)); }
    public boolean dropThrough(PlayerId id) {
        var pos=players.getPosition(id);
        for(var p:platforms) {
            float top=(p.yPixels()+p.heightPixels())/100;
            if(pos.xMeters()>=p.xPixels()/100 && pos.xMeters()<=(p.xPixels()+p.widthPixels())/100 && Math.abs(pos.yMeters()-.05f-top)<.04f) {
                dropTicks.put(id,15);jumps.resetAfterRespawn(id);velocity(id,players.getVelocity(id).xMetersPerSecond(),-1);return true;
            }
        }
        return false;
    }
    public void velocity(PlayerId id,float x,float y) {
        if(!Float.isFinite(x) || !Float.isFinite(y)) { throw new IllegalArgumentException("Velocity"); }
        players.getBody(id).setLinearVelocity(x,y);
    }
    public Set<PlayerId> targets(float left,float bottom,float right,float top) {
        var result=new HashSet<PlayerId>();
        world.QueryAABB(fixture->{
            if(fixture.getUserData() instanceof Hurtbox hurt) {
                var p=players.getPosition(hurt.id);float dx=p.xMeters()-Math.max(left,Math.min(p.xMeters(),right));
                // Closest segment-to-box distance, exact for the axis-aligned capsule hurtbox.
                float low=p.yMeters()-.01f,high=p.yMeters()+.17f;
                float dy=high<bottom?bottom-high:low>top?low-top:0;
                if(dx*dx+dy*dy<=.085f*.085f) { result.add(hurt.id); }
            }
            return true;
        },left,bottom,right,top);
        return result;
    }
    public void reset(PlayerId id) { dropTicks.remove(id);jumps.resetAfterRespawn(id); }
    public void bounceFromLava(PlayerId id) {
        var body=players.getBody(id);float vx=body.getLinearVelocity().x;
        body.setTransform(body.getPosition().x,com.smashprofs.common.model.FighterArena.LAVA_CENTER_Y,0);
        body.setLinearVelocity(vx,com.smashprofs.common.model.FighterArena.LAVA_BOUNCE_SPEED);reset(id);
    }
    @Override public void close() { dropTicks.clear();jumps.setContactPolicy(null); }
}
