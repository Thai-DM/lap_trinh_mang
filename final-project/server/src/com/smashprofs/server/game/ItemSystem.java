package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import java.util.*;

/** Bounded simulation-time item/projectile system. Ballistic grenades use fixed-step gravity and swept collisions. */
final class ItemSystem implements AutoCloseable {
    static final int MAX_ITEMS=16,MAX_SHOTS=64;
    private static final String[] TYPES={"SHOTGUN","LASER","BAZOOKA","GRENADE","HEALTH","SHIELD"};
    private final GameplayMatch game;
    private final Random random;
    private final List<Platform> platforms;
    private final List<Platform> terrain;
    private final List<Drop> drops=new ArrayList<>(MAX_ITEMS);
    private final List<Shot> shots=new ArrayList<>(MAX_SHOTS);
    private long nextDrop,nextId=1;
    ItemSystem(GameplayMatch game,MapCollisionDefinition geometry,Random random) {
        this.game=game;this.random=random;
        terrain=geometry.rectangles().stream().filter(r->r.widthPixels()>0 || r.heightPixels()>0)
                .map(r->new Platform(r.xPixels()/100,(r.xPixels()+r.widthPixels())/100,(r.yPixels()+r.heightPixels())/100,r.yPixels()/100))
                .toList();
        platforms=terrain.stream().filter(p->p.right()-p.left()>.4f && p.top()>p.bottom() && p.left()>=3 && p.right()<=8.5f && p.top()>0.1f && p.top()<3.8f).toList();
        if(platforms.isEmpty()) { throw new IllegalArgumentException("No safe item platform"); }
        nextDrop=game.setup.rules().dropRate().intervalTicks;
    }
    void update(long tick) {
        int interval=game.setup.rules().dropRate().intervalTicks;
        if(interval>0 && tick>=nextDrop) {
            nextDrop=tick+interval;
            if(drops.size()<MAX_ITEMS) {
                for(int attempt=0;attempt<16;attempt++) {
                    var p=platforms.get(random.nextInt(platforms.size()));float x=p.left()+0.12f+random.nextFloat()*Math.max(0,p.right()-p.left()-0.24f),y=p.top()+0.12f;
                    boolean clear=true;
                    for(var block:terrain) {
                        float dx=x-Math.max(block.left,Math.min(x,block.right)),dy=y-Math.max(block.bottom,Math.min(y,block.top));
                        if(dx*dx+dy*dy<.08f*.08f) { clear=false;break; }
                    }
                    if(clear) { drop(TYPES[random.nextInt(TYPES.length)],x,y,tick);break; }
                }
            }
        }
        drops.removeIf(drop->tick-drop.tick>1800);
        for(var iterator=drops.iterator();iterator.hasNext();) {
            var drop=iterator.next();
            // Stable slot order resolves simultaneous pickup; distance is verified on server bodies.
            for(var p:game.state.playersInSlotOrder()) {
                var id=p.getPlayerId();if(!game.active(id)) { continue; }
                var pos=game.bodies.getPosition(id);
                if(Math.hypot(pos.xMeters()-drop.x,pos.yMeters()-drop.y)<=0.25) {
                    collect(id,drop.type);iterator.remove();break;
                }
            }
        }
        for(var iterator=shots.iterator();iterator.hasNext();) {
            var shot=iterator.next();
            if(!game.active(shot.owner) || tick-shot.tick>180 || shot.x<0 || shot.x>12 || shot.y<-.5f || shot.y>5) { iterator.remove();continue; }
            if(shot.type.equals("EXPLOSION")) { if(tick-shot.tick>12) { iterator.remove(); }continue; }
            float previousX=shot.x,previousY=shot.y;
            if(shot.type.equals("ORBIT")) {
                var pos=game.bodies.getPosition(shot.owner);double angle=(tick-shot.tick)*.10+shot.phase;
                shot.x=pos.xMeters()+.32f*(float)Math.cos(angle);shot.y=pos.yMeters()+.32f*(float)Math.sin(angle);
            } else {
                if(shot.type.equals("GRENADE")) { shot.vy-=3f/60; }
                shot.x+=shot.vx/60;shot.y+=shot.vy/60;
            }
            boolean piercing=shot.type.equals("LASER") || shot.type.equals("ORBIT");
            float wallFraction=piercing?Float.POSITIVE_INFINITY:terrainFraction(previousX,previousY,shot.x,shot.y);
            float firstFraction=wallFraction;PlayerId firstTarget=null;
            for(var p:game.state.playersInSlotOrder()) {
                var target=p.getPlayerId();if(target.equals(shot.owner) || !game.active(target) || shot.hit.contains(target)) { continue; }
                var pos=game.bodies.getPosition(target);
                float fraction=circleFraction(pos.xMeters(),pos.yMeters(),previousX,previousY,shot.x,shot.y,.085f);
                if(fraction<=1 && piercing) {
                    shot.hit.add(target);
                    float damage=shot.type.equals("LASER")?24:shot.type.equals("ORBIT")?10:12;
                    float direction=shot.type.equals("ORBIT")?(pos.xMeters()>=game.bodies.getPosition(shot.owner).xMeters()?1:-1):(shot.vx>=0?1:-1);
                    game.damage(shot.owner,target,shot.type,damage,direction,1.5f);
                } else if(fraction<=1 && fraction<firstFraction) { firstFraction=fraction;firstTarget=target; }
            }
            boolean collision=firstTarget!=null,terrain=!piercing && !collision && wallFraction<=1;
            if(!piercing && firstFraction<=1) {
                shot.x=previousX+(shot.x-previousX)*firstFraction;shot.y=previousY+(shot.y-previousY)*firstFraction;
                if(collision && shot.type.equals("SHOTGUN")) {
                    game.damage(shot.owner,firstTarget,shot.type,12,shot.vx>=0?1:-1,1.5f);
                }
            }
            if(shot.type.equals("GRENADE")) {
                if(terrain) { shot.x=previousX;shot.y=previousY;shot.vx*=.5f;shot.vy=Math.abs(shot.vy)*.3f; }
                if(collision || tick-shot.tick>=120) { explode(shot,tick); }
            } else if(shot.type.equals("BAZOOKA")) {
                if(collision || terrain) { explode(shot,tick); }
            } else if(!shot.type.equals("LASER") && !shot.type.equals("ORBIT") && (collision || terrain)) { iterator.remove(); }
        }
    }
    /** Simulation/test seam; not exposed through protocol. Normal drops use validated platform candidates. */
    boolean drop(String type,float x,float y,long tick) {
        if(drops.size()>=MAX_ITEMS) { return false; }
        if(!List.of(TYPES).contains(type) || !Float.isFinite(x) || !Float.isFinite(y)) { throw new IllegalArgumentException("Invalid drop"); }
        drops.add(new Drop(nextId++,type,x,y,tick));return true;
    }
    private void collect(PlayerId id,String type) {
        var p=game.progress.get(id);p.pickups++;
        switch(type) {
            case "HEALTH"->game.state.getPlayer(id).heal(30);
            case "SHIELD"->p.shield=true;
            default->{p.weapon=type;p.ammo=type.equals("GRENADE")?1:6;p.weapons++;}
        }
    }
    boolean attack(PlayerId id,long tick) {
        var p=game.progress.get(id);
        if(!game.active(id) || p.weapon.equals("NONE")) { return false; }
        // An equipped weapon consumes the melee edge even during its fire cooldown.
        if(tick<p.nextShot) { return true; }
        int count=p.weapon.equals("SHOTGUN")?3:1;
        if(shots.size()+count>MAX_SHOTS) { return true; }
        var pos=game.bodies.getPosition(id);float direction=game.state.getPlayer(id).isFacingRight()?1:-1;
        float speed=p.weapon.equals("LASER")?40:p.weapon.equals("GRENADE")?1.8f:p.weapon.equals("BAZOOKA")?2:4;
        for(int i=0;i<count;i++) {
            float angle=count==3?(i-1)*.20f:0;
            shots.add(new Shot(nextId++,p.weapon,id,tick,pos.xMeters(),pos.yMeters(),
                    direction*speed*(float)Math.cos(angle),p.weapon.equals("GRENADE")?2.2f:speed*(float)Math.sin(angle),0));
        }
        p.nextShot=tick+24;if(--p.ammo==0) { p.weapon="NONE"; }
        return true;
    }
    boolean orbit(PlayerId id,long tick) {
        if(shots.size()+2>MAX_SHOTS) { return false; }
        var pos=game.bodies.getPosition(id);
        for(int i=0;i<2;i++) { shots.add(new Shot(nextId++,"ORBIT",id,tick,pos.xMeters(),pos.yMeters(),0,0,i*(float)Math.PI)); }
        return true;
    }
    private void explode(Shot shot,long tick) {
        for(var p:game.state.playersInSlotOrder()) {
            var id=p.getPlayerId();if(id.equals(shot.owner) || !game.active(id)) { continue; }
            var pos=game.bodies.getPosition(id);float dx=pos.xMeters()-shot.x,dy=pos.yMeters()-shot.y;
            if(Math.hypot(dx,dy)<=.85f) { game.damage(shot.owner,id,shot.type,32,dx>=0?2.2f:-2.2f,2.8f); }
        }
        shot.type="EXPLOSION";shot.tick=tick;shot.vx=0;shot.vy=0;
    }
    private float terrainFraction(float ax,float ay,float bx,float by) {
        float closest=Float.POSITIVE_INFINITY;
        for(var p:terrain) {
            closest=Math.min(closest,boxFraction(ax,ay,bx,by,p.left-.035f,p.right+.035f,p.bottom-.035f,p.top+.035f));
        }
        return closest;
    }
    private static float boxFraction(float ax,float ay,float bx,float by,float left,float right,float bottom,float top) {
        float dx=bx-ax,dy=by-ay,enter=0,exit=1;
        if(dx==0) { if(ax<left || ax>right) { return Float.POSITIVE_INFINITY; } }
        else { float a=(left-ax)/dx,b=(right-ax)/dx;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b)); }
        if(dy==0) { if(ay<bottom || ay>top) { return Float.POSITIVE_INFINITY; } }
        else { float a=(bottom-ay)/dy,b=(top-ay)/dy;enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b)); }
        return enter<=exit?enter:Float.POSITIVE_INFINITY;
    }
    private static float circleFraction(float x,float y,float ax,float ay,float bx,float by,float radius) {
        float dx=bx-ax,dy=by-ay,ox=ax-x,oy=ay-y,c=ox*ox+oy*oy-radius*radius;
        if(c<=0) { return 0; }float a=dx*dx+dy*dy;if(a==0) { return Float.POSITIVE_INFINITY; }
        float b=2*(ox*dx+oy*dy),discriminant=b*b-4*a*c;
        if(discriminant<0) { return Float.POSITIVE_INFINITY; }
        float t=(-b-(float)Math.sqrt(discriminant))/(2*a);return t>=0 && t<=1?t:Float.POSITIVE_INFINITY;
    }
    List<GameplaySnapshot.Item> itemViews() { return drops.stream().map(p->new GameplaySnapshot.Item(p.id,p.type,p.x,p.y)).toList(); }
    List<GameplaySnapshot.Shot> shotViews() { return shots.stream().map(p->new GameplaySnapshot.Shot(p.id,p.type,p.owner.value(),p.x,p.y,p.vx,p.vy)).toList(); }
    @Override public void close() { drops.clear();shots.clear(); }
    private record Drop(long id,String type,float x,float y,long tick) { }
    private record Platform(float left,float right,float top,float bottom) { }
    private static final class Shot {
        final long id;String type;final PlayerId owner;long tick;float x,y,vx,vy;final float phase;final Set<PlayerId> hit=new HashSet<>();
        Shot(long id,String type,PlayerId owner,long tick,float x,float y,float vx,float vy,float phase) { this.id=id;this.type=type;this.owner=owner;this.tick=tick;this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.phase=phase; }
    }
}
