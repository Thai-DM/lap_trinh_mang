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
    private final Map<PlayerId,Set<MapCollisionDefinition.CollisionRectangle>> droppedPlatform=new HashMap<>();
    private final Map<PlayerId,Float> hurtboxHigh=new HashMap<>();
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
        return r.heightPixels()>0 && r.heightPixels()<=40 && r.widthPixels()>=8 && (r.yPixels()+r.heightPixels())/100>.65f;
    }
    private void filter(Contact contact) {
        Fixture a=contact.getFixtureA(),b=contact.getFixtureB();
        if(a.getBody().getUserData() instanceof PlayerId id) { filter(contact,id,b); }
        if(b.getBody().getUserData() instanceof PlayerId id) { filter(contact,id,a); }
    }
    private void filter(Contact contact,PlayerId id,Fixture floor) {
        if(!(floor.getUserData() instanceof MapCollisionDefinition.CollisionRectangle r) || !oneWay(r)) { return; }
        var body=players.getBody(id);float top=(r.yPixels()+r.heightPixels())/100;
        boolean droppingThis=dropTicks.getOrDefault(id,0)>0 && droppedPlatform.getOrDefault(id,Set.of()).contains(r);
        if(droppingThis || body.getLinearVelocity().y>0 || body.getPosition().y-.05f<top-.025f) { contact.setEnabled(false); }
    }
    public void tick() { dropTicks.replaceAll((id,ticks)->{ int next=Math.max(0,ticks-1); if(next==0) { droppedPlatform.remove(id); } return next; }); }
    public boolean dropThrough(PlayerId id) {
        var pos=players.getPosition(id);
        for(var p:platforms) {
            float top=(p.yPixels()+p.heightPixels())/100;
            if(pos.xMeters()>=p.xPixels()/100 && pos.xMeters()<=(p.xPixels()+p.widthPixels())/100 && Math.abs(pos.yMeters()-.05f-top)<.06f) {
                var surfaces=platforms.stream().map(r->new com.smashprofs.common.model.PlatformAssembly.Surface(r.xPixels()/100,
                        (r.yPixels()+r.heightPixels())/100,r.widthPixels()/100)).toList();
                var assembly=new HashSet<MapCollisionDefinition.CollisionRectangle>();
                for(int index:com.smashprofs.common.model.PlatformAssembly.connected(surfaces,platforms.indexOf(p))) { assembly.add(platforms.get(index)); }
                dropTicks.put(id,12);droppedPlatform.put(id,Set.copyOf(assembly));jumps.resetAfterRespawn(id);velocity(id,players.getVelocity(id).xMetersPerSecond(),-1);return true;
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
                float low=p.yMeters()-.01f,high=p.yMeters()+hurtboxHigh.getOrDefault(hurt.id,.17f);
                float dy=high<bottom?bottom-high:low>top?low-top:0;
                if(dx*dx+dy*dy<=.085f*.085f) { result.add(hurt.id); }
            }
            return true;
        },left,bottom,right,top);
        return result;
    }
    public void reset(PlayerId id) { dropTicks.remove(id);droppedPlatform.remove(id);jumps.resetAfterRespawn(id); }
    public void hurtbox(PlayerId id,String move,int frame) { hurtboxHigh.put(id,com.smashprofs.common.model.FighterMoves.hurtboxHigh(move,frame)); }
    public void bounceFromLava(PlayerId id) {
        var body=players.getBody(id);float vx=body.getLinearVelocity().x;
        body.setTransform(body.getPosition().x,com.smashprofs.common.model.FighterArena.LAVA_CENTER_Y,0);
        body.setLinearVelocity(vx,com.smashprofs.common.model.FighterArena.LAVA_BOUNCE_SPEED);reset(id);
    }
    @Override public void close() { dropTicks.clear();droppedPlatform.clear();hurtboxHigh.clear();jumps.setContactPolicy(null); }
}
