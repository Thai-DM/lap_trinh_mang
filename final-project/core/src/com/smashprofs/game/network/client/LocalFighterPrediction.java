package com.smashprofs.game.network.client;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.common.model.*;
import java.util.*;

/** Render-owned, isolated movement world. It never writes authoritative HP, stocks or hits. */
public final class LocalFighterPrediction implements AutoCloseable {
    public record Rect(float x,float y,float width,float height) {
        boolean oneWay() { return height>0 && height<=.4f && width>=.3f && y+height>.65f; }
    }
    public record Position(float x,float y) { }
    private final World world;
    private final Body body;
    private final List<Rect> geometry;
    private int dropTicks;
    private Rect droppedPlatform;
    private Position shown;
    private String lastAction;
    public LocalFighterPrediction(List<Rect> geometry) {
        Box2D.init();this.geometry=List.copyOf(geometry);world=new World(new Vector2(),true);
        var box=new PolygonShape();var circle=new CircleShape();
        try {
            for(var r:this.geometry) {
                var def=new BodyDef();def.position.set(r.x+r.width/2,r.y+r.height/2);var tile=world.createBody(def);
                box.setAsBox(r.width/2,r.height/2);tile.createFixture(box,0).setUserData(r);
            }
            var def=new BodyDef();def.type=BodyDef.BodyType.DynamicBody;def.fixedRotation=true;body=world.createBody(def);
            circle.setRadius(.05f);body.createFixture(circle,0);
        } finally { box.dispose();circle.dispose(); }
        world.setContactListener(new ContactListener() {
            public void beginContact(Contact c) { }public void endContact(Contact c) { }public void postSolve(Contact c,ContactImpulse i) { }
            public void preSolve(Contact c,Manifold old) {
                var a=c.getFixtureA();var b=c.getFixtureB();var floor=a.getBody()==body?b:a;
                if(floor.getUserData() instanceof Rect r && r.oneWay()
                        && ((dropTicks>0 && r==droppedPlatform) || body.getLinearVelocity().y>0 || body.getPosition().y-.05f<r.y+r.height-.025f)) { c.setEnabled(false); }
            }
        });
    }
    public Position sample(WireGameSnapshot.Player authoritative,FighterSnapshot.Player fighter,List<PlayerInput> inputs,float delta) {
        body.setTransform(authoritative.x(),authoritative.y(),0);body.setLinearVelocity(authoritative.velocityX(),authoritative.velocityY());dropTicks=0;droppedPlatform=null;
        boolean grounded=authoritative.grounded(),jumping=body.getLinearVelocity().y>1.8f;
        var air=new AirActionBudget(fighter.airJumps(),fighter.detail().recoveries(),fighter.detail().aerialActionsUsed());
        int wallJumps=fighter.wallJumps(),wall=0,wallLock=fighter.detail().wallLockTicks(),dashTicks=fighter.detail().dashTicks(),lavaStun=0;
        int runTicks=fighter.detail().runTicks(),dashCooldown=fighter.detail().dashCooldownTicks(),wallActions=fighter.detail().wallActions(),previousWall=0;
        float dashVelocity=fighter.detail().dashVelocity();
        boolean idle=fighter.action().equals("IDLE");
        // Group render samples in the 60 Hz client tick domain; 120 FPS never means twice the movement.
        var groups=new TreeMap<Long,PlayerInput>();
        if(idle && !authoritative.dead()) {
            for(var input:inputs) {
                if(input.sequence()<=fighter.acknowledgedSequence()) { continue; }
                var previous=groups.get(input.clientTick());
                if(previous==null) { groups.put(input.clientTick(),input); }
                else { groups.put(input.clientTick(),new PlayerInput(input.playerId(),input.sequence(),input.clientTick(),input.moveAxis(),
                        previous.jumpPressed()||input.jumpPressed(),false,previous.meleePressed()||input.meleePressed(),false,false,
                        previous.interactPressed()||input.interactPressed(),previous.dropPressed()||input.dropPressed(),previous.fighter().merge(input.fighter()))); }
            }
        }
        long previousTick=fighter.acknowledgedClientTick();int simulated=0;
        replay: for(var input:groups.values()) {
            // Attacks/air dodges stay authoritative. Never replay later movement past an unknown combat transition.
            if(input.meleePressed() || input.fighter().heavyPressed() || input.fighter().dodgePressed()) { break replay; }
            int steps=(int)Math.min(15,Math.max(1,input.clientTick()>=previousTick?input.clientTick()-previousTick:1));previousTick=input.clientTick();
            for(int step=0;step<steps && simulated<15;step++,simulated++) {
                if(dropTicks>0) { dropTicks--; }if(wallLock>0) { wallLock--; }if(dashTicks>0) { dashTicks--; }
                if(dashCooldown>0) { dashCooldown--; }
                if(lavaStun>0) {
                    var v=body.getLinearVelocity();body.setLinearVelocity(v.x,v.y-9.8f/60);lavaStun--;
                } else {
                var v=body.getLinearVelocity();boolean jump=step==0 && input.jumpPressed(),wasGrounded=grounded;
                if(grounded) { air.reset();wallJumps=3;wallActions=0; }
                else if(wall!=0 && previousWall==0 && wallActions<UnarmedRules.WALL_SLIP) { air.reset(); }
                previousWall=wall;
                boolean dropping=false;
                if(grounded && (input.dropPressed() || (jump && input.fighter().downHeld()))) {
                    for(var r:geometry) { if(r.oneWay() && body.getPosition().x>=r.x && body.getPosition().x<=r.x+r.width && Math.abs(body.getPosition().y-.05f-r.y-r.height)<.04f) { dropping=true;droppedPlatform=r;break; } }
                }
                if(dropping) { dropTicks=12;body.setLinearVelocity(v.x,-1);grounded=false;jumping=false; }
                else if(jump) {
                    if(!grounded && wall!=0 && wallLock==0 && wallActions<UnarmedRules.WALL_SLIP) { body.setLinearVelocity(wall*.6f,4.2f);air.reset();wallLock=12;jumping=true;wallActions++; }
                    else if(grounded || air.jump()) { body.setLinearVelocity(v.x,4.2f);if(!grounded) { wallActions++; }jumping=true;grounded=false; }
                }
                if(step==0 && input.fighter().dashPressed() && wasGrounded && dashCooldown==0) {
                    dashTicks=12;dashCooldown=24;dashVelocity=(input.moveAxis()!=0?Math.signum(input.moveAxis()):authoritative.facingRight()?1:-1)*FighterMovement.DASH_SPEED;
                }
                runTicks=grounded && input.moveAxis()!=0?Math.min(18,runTicks+1):0;
                v=body.getLinearVelocity();var move=FighterMovement.step(v.x,v.y,input.moveAxis(),FighterStats.forCharacter(authoritative.characterId()).speed(),
                        grounded,input.fighter().downHeld(),jumping && !input.fighter().jumpHeld(),wallLock<=8,runTicks>=18);
                float x=dashTicks>0?dashVelocity:move.x(),y=move.y();
                if(jumping && !input.fighter().jumpHeld()) { jumping=false; }
                if(wall!=0 && input.moveAxis()*wall<0 && wallLock==0) { x=0;y=Math.max(y,wallActions>=UnarmedRules.WALL_SLIP?-1.5f:-.55f); }
                body.setLinearVelocity(x,y);
                }
                world.step(1/60f,6,2);
                grounded=false;wall=0;
                for(var contact:world.getContactList()) {
                    if(!contact.isTouching() || !contact.isEnabled()) { continue; }
                    float sign=contact.getFixtureA().getBody()==body?-1:1;var normal=contact.getWorldManifold().getNormal();
                    if(sign*normal.y>=.5f && body.getLinearVelocity().y<=.0001f) { grounded=true; }
                    if(Math.abs(normal.x)>.7f) { wall=sign*normal.x>0?1:-1; }
                }
                var position=body.getPosition();var velocity=body.getLinearVelocity();
                if(!grounded && FighterArena.touchesLava(position.x,position.y,velocity.y)) {
                    body.setTransform(position.x,FighterArena.LAVA_CENTER_Y,0);body.setLinearVelocity(velocity.x,FighterArena.LAVA_BOUNCE_SPEED);
                    lavaStun=FighterArena.LAVA_STUN_TICKS;jumping=false;air.reset();wallJumps=3;wall=0;wallLock=0;dashTicks=0;dropTicks=0;
                }
            }
            if(simulated>=15) { break; }
        }
        var pos=body.getPosition();var target=new Position(pos.x,pos.y);
        boolean discontinuity=shown==null || authoritative.dead() || !Objects.equals(lastAction,fighter.action())
                || Math.hypot(target.x-shown.x,target.y-shown.y)>1;
        float alpha=discontinuity?1:(float)-Math.expm1(-30*Math.max(0,Math.min(.1f,delta)));
        shown=shown==null?target:new Position(shown.x+(target.x-shown.x)*alpha,shown.y+(target.y-shown.y)*alpha);
        lastAction=fighter.action();return shown;
    }
    @Override public void close() { world.dispose(); }
}
