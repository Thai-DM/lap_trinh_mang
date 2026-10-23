package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.MapCollisionDefinition;
import java.util.*;

/** Kinematic boss on a validated platform. All decisions, hit tests and timers belong to the simulation owner. */
final class BossEngine {
    static final float HALF_WIDTH=.30f,HEIGHT=.72f;
    private static final float FOLLOW_DISTANCE=.65f,WALK_SPEED=.70f,ENRAGED_WALK_SPEED=.95f;
    final GameplayMatch game;
    final float maxHp,left,right;
    float x,y,hp;
    boolean facingRight=true;
    int phase=1;
    String action="IDLE",attack="NONE";
    long tick,actionAt,until=90,attackId,hitSerial,nextHazard=1;
    int pattern;
    private final Set<PlayerId> cleaveHits=new HashSet<>();
    private final List<Hazard> hazards=new ArrayList<>();
    private final MapCollisionDefinition geometry;
    private PlayerId chaseTarget,attackTarget;

    BossEngine(GameplayMatch game,MapCollisionDefinition geometry) {
        this.game=game;this.geometry=geometry;maxHp=1000+250*(game.progress.size()-2);hp=maxHp;
        var platform=geometry.rectangles().stream().filter(r->r.widthPixels()>=100 && r.heightPixels()>0
                && r.xPixels()/100>=2 && (r.xPixels()+r.widthPixels())/100<=10
                && (r.yPixels()+r.heightPixels())/100>.1f && (r.yPixels()+r.heightPixels())/100<1.5f)
                .max(Comparator.comparingDouble(MapCollisionDefinition.CollisionRectangle::widthPixels)).orElseThrow(()->new IllegalArgumentException("Boss needs a safe main platform"));
        left=platform.xPixels()/100+HALF_WIDTH;right=(platform.xPixels()+platform.widthPixels())/100-HALF_WIDTH;
        x=(left+right)/2;y=(platform.yPixels()+platform.heightPixels())/100;
    }
    void update(long tick) {
        this.tick=tick;
        if(hp<=0) { hazards.clear();return; }
        if(hp<=maxHp/2) { phase=2; }
        updateHazards();
        if(tick>=until) {
            switch(action) {
                case "TELEGRAPH" -> {
                    change("ACTIVE",attack.equals("CLEAVE")?12:1);
                    if(attack.equals("FIREBALL")) { fire(); }
                }
                case "ACTIVE" -> change("RECOVERY",phase==1?60:42);
                case "RECOVERY" -> { attack="NONE";change("IDLE",phase==1?45:24); }
                default -> prepare();
            }
        }
        if(action.equals("IDLE")||action.equals("WALK")) {
            var target=target();
            if(target!=null) {
                float dx=game.bodies.getPosition(target).xMeters()-x;
                if(Math.abs(dx)>.02f) { facingRight=dx>0; }
                float step=Math.min((phase==1?WALK_SPEED:ENRAGED_WALK_SPEED)/60,Math.max(0,Math.abs(dx)-FOLLOW_DISTANCE));
                float next=Math.max(left,Math.min(right,x+Math.signum(dx)*step));
                // Do not slide past the target or play a walk animation against the platform edge.
                action=Math.abs(next-x)>.000001f?"WALK":"IDLE";x=next;
            } else { action="IDLE"; }
        }
        if(action.equals("ACTIVE") && attack.equals("CLEAVE")) {
            for(var id:game.progress.keySet()) {
                if(!game.active(id)||cleaveHits.contains(id)) { continue; }
                var p=game.bodies.getPosition(id);
                float dx=(p.xMeters()-x)*(facingRight?1:-1);
                if(dx>=-.1f&&dx<=1.05f&&p.yMeters()+.18f>=y&&p.yMeters()-.1f<=y+.7f) {
                    cleaveHits.add(id);hurt(id,22,facingRight?1:-1,.6f);
                }
            }
        }
    }
    private PlayerId target() {
        // HP is authoritative; lives, distance and client identity never override a lower HP target.
        // Keep an equally wounded current target, then use distance/ID for a deterministic first choice.
        chaseTarget=game.progress.keySet().stream().filter(game::active).min(Comparator.<PlayerId>comparingDouble(id->game.state.getPlayer(id).getHp())
                .thenComparingInt(id->id.equals(chaseTarget)?0:1).thenComparingDouble(id->{
            var p=game.bodies.getPosition(id);return Math.abs(p.xMeters()-x);
        }).thenComparing(PlayerId::value)).orElse(null);
        return chaseTarget;
    }
    private void prepare() {
        var target=target();
        if(target==null) { change("IDLE",15);return; }
        attackTarget=target;
        var p=game.bodies.getPosition(target);facingRight=p.xMeters()>=x;attackId++;cleaveHits.clear();
        attack=Math.abs(p.xMeters()-x)<.85f && pattern%3!=2?"CLEAVE":pattern%2==0?"FIREBALL":"ERUPTION";pattern++;
        change("TELEGRAPH",phase==1?48:36);
        if(attack.equals("ERUPTION")) {
            for(var id:game.progress.keySet()) {
                if(!game.active(id)||hazards.size()>=BossSnapshot.MAX_HAZARDS) { continue; }
                var position=game.bodies.getPosition(id);
                hazards.add(new Hazard(nextHazard++,"ERUPTION",position.xMeters(),groundBelow(position.xMeters(),position.yMeters()),0,0,.42f,tick+60,tick+75));
            }
        }
    }
    private void change(String value,int duration) { action=value;actionAt=tick;until=tick+duration; }
    private float groundBelow(float x,float y) {
        float ground=FighterArena.LAVA_TOP;
        for(var r:geometry.rectangles()) {
            float top=(r.yPixels()+r.heightPixels())/100;
            if(r.widthPixels()>0 && r.heightPixels()>0 && x>=r.xPixels()/100 && x<=(r.xPixels()+r.widthPixels())/100 && top<=y+.08f) { ground=Math.max(ground,top); }
        }
        return ground;
    }
    private void fire() {
        // Preserve the target announced by the windup; choose again on the next pursuit cycle.
        var target=attackTarget;if(target==null || !game.active(target)) { return; }
        var p=game.bodies.getPosition(target);float dx=p.xMeters()-x,dy=p.yMeters()-(y+.35f);
        double angle=Math.atan2(dy,dx);int count=phase==1?1:3;
        for(int i=0;i<count && hazards.size()<BossSnapshot.MAX_HAZARDS;i++) {
            double a=angle+(i-(count-1)/2f)*.22;
            hazards.add(new Hazard(nextHazard++,"FIREBALL",x,y+.35f,(float)Math.cos(a)*2.3f,(float)Math.sin(a)*2.3f,.10f,tick,tick+240));
        }
    }
    private void updateHazards() {
        var it=hazards.iterator();
        while(it.hasNext()) {
            var h=it.next();if(tick>=h.expires) { it.remove();continue; }
            float ax=h.x,ay=h.y;h.x+=h.vx/60;h.y+=h.vy/60;
            if(tick<h.activeAt) { continue; }
            boolean consumed=false;
            for(var id:game.progress.keySet()) {
                if(!game.active(id)||h.hit.contains(id)) { continue; }
                var p=game.bodies.getPosition(id);
                boolean hit=h.type.equals("FIREBALL")?distanceToSegment(p.xMeters(),p.yMeters()+.06f,ax,ay,h.x,h.y)<=h.radius+.12f:
                        Math.abs(p.xMeters()-h.x)<=h.radius+.08f&&Math.abs(p.yMeters()-h.y)<=.45f;
                if(hit) {
                    h.hit.add(id);hurt(id,h.type.equals("FIREBALL")?16:24,p.xMeters()>=x?1:-1,1);
                    if(h.type.equals("FIREBALL")) { consumed=true;break; }
                }
            }
            if(consumed) { it.remove(); }
        }
    }
    private void hurt(PlayerId id,float damage,float dx,float dy) {
        game.fighter.hit(null,id,"BOSS",damage,.65f,.1f,dx,dy,false);
    }
    boolean damage(PlayerId owner,float amount) {
        if(!Float.isFinite(amount)||amount<=0) { throw new IllegalArgumentException("Boss damage"); }
        if(hp<=0||!game.active(owner)) { return false; }
        float dealt=Math.min(hp,amount);hp-=dealt;game.progress.get(owner).damage+=dealt;hitSerial++;
        if(hp==0) { action="DEAD";actionAt=tick;attack="NONE";hazards.clear(); }
        return true;
    }
    boolean overlaps(float l,float b,float r,float t) { return hp>0 && r>=x-HALF_WIDTH&&l<=x+HALF_WIDTH&&t>=y&&b<=y+HEIGHT; }
    float fraction(float ax,float ay,float bx,float by) {
        return ItemSystem.boxFraction(ax,ay,bx,by,x-HALF_WIDTH,x+HALF_WIDTH,y,y+HEIGHT);
    }
    BossSnapshot snapshot() {
        return new BossSnapshot(x,y,facingRight,hp,maxHp,phase,action,attack,actionAt,attackId,hitSerial,
            hazards.stream().map(h->new BossSnapshot.Hazard(h.id,h.type,h.x,h.y,h.vx,h.vy,h.radius,Math.max(0,(tick<h.activeAt?h.activeAt:h.expires)-tick),tick>=h.activeAt)).toList());
    }
    private static double distanceToSegment(float x,float y,float ax,float ay,float bx,float by) {
        float dx=bx-ax,dy=by-ay;float length=dx*dx+dy*dy,t=length==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/length));
        return Math.hypot(x-ax-t*dx,y-ay-t*dy);
    }
    private static final class Hazard {
        final long id,activeAt,expires;final String type;final float vx,vy,radius;float x,y;final Set<PlayerId> hit=new HashSet<>();
        Hazard(long id,String type,float x,float y,float vx,float vy,float radius,long activeAt,long expires) {
            this.id=id;this.type=type;this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.radius=radius;this.activeAt=activeAt;this.expires=expires;
        }
    }
}
