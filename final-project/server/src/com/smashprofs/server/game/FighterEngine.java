package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.World;
import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import java.util.*;

/** One simulation owner, fixed 60 Hz. Combat uses percent; lava depletes HP before stock respawn. */
final class FighterEngine implements AutoCloseable {
    static final float LEFT=1,RIGHT=11.5f,BOTTOM=-1.5f,TOP=9;
    final GameplayMatch game;
    final FighterPhysics physics;
    final Map<PlayerId,Fighter> fighters=new LinkedHashMap<>();
    private final float spawnY;
    private long tick,nextAttackId=1;
    FighterEngine(GameplayMatch game,World world,MapCollisionDefinition geometry) {
        this.game=game;physics=new FighterPhysics(world,game.bodies,game.jumps,geometry);
        spawnY=geometry.mapHeightPixels()/100-.55f;
        for(var p:game.setup.participants()) { fighters.put(p.playerId(),new Fighter(FighterStats.forCharacter(p.characterId()))); }
    }
    SpawnProvider.SpawnPosition respawnPosition(PlayerId id) {
        return new SpawnProvider.SpawnPosition(6+(game.state.getPlayer(id).getSlotIndex()-1.5f)*.22f,spawnY);
    }
    void respawn(PlayerId id,long tick) {
        var f=fighters.get(id);f.damage=0;f.attack=null;f.hitstun=0;f.hitstop=0;f.frozen=false;
        f.nextDodge=0;f.dodgeStart=-1000;f.dashUntil=0;f.platformUntil=tick+180;f.nextLavaDamage=0;
        f.airJumps=1;f.wallJumps=3;f.airDodgeUsed=false;f.recovery=true;f.fastFall=false;f.jumping=false;
        f.axis=0;f.held=FighterInput.NONE;physics.reset(id);game.progress.get(id).invulnerableUntil=0;
    }
    void update(long tick,List<PlayerInput> inputs) {
        this.tick=tick;physics.tick();var requests=new HashMap<PlayerId,PlayerInput>();
        for(var input:inputs) {
            if(!game.active(input.playerId())) { continue; }
            requests.put(input.playerId(),input);var f=fighters.get(input.playerId());
            f.axis=input.moveAxis();f.held=input.fighter().heldOnly();f.lastInput=tick;
            f.acknowledgedSequence=input.sequence();f.acknowledgedClientTick=input.clientTick();
        }
        for(var entry:fighters.entrySet()) {
            var id=entry.getKey();var f=entry.getValue();
            if(!game.active(id)) { f.attack=null;f.fastFall=false;continue; }
            if(tick-f.lastInput>=15) { f.axis=0;f.held=FighterInput.NONE; }
            var input=requests.get(id);var velocity=game.bodies.getVelocity(id);
            if(f.platformUntil>0) {
                boolean leave=tick>=f.platformUntil || input!=null && (input.moveAxis()!=0 || input.jumpPressed() || input.meleePressed()
                        || input.interactPressed() || input.dropPressed() || !input.fighter().equals(FighterInput.NONE));
                if(!leave) { physics.velocity(id,0,0);continue; }
                f.platformUntil=0;game.progress.get(id).invulnerableUntil=tick+120;
            }
            if(f.hitstop>0) { continue; }
            if(f.frozen) { physics.velocity(id,f.frozenX,f.frozenY);f.frozen=false;velocity=game.bodies.getVelocity(id); }
            boolean grounded=game.jumps.getJumpState(id).grounded();int wall=game.jumps.wallDirection(id);
            if(grounded) { f.airJumps=1;f.wallJumps=3;f.airDodgeUsed=false;f.recovery=true;f.jumping=false; }
            if(wall!=0 && f.lastWall==0 && f.wallJumps>0) { f.airJumps=1; }
            f.lastWall=wall;
            if(f.hitstun>0) {
                f.hitstun--;f.attack=null;var v=game.bodies.getVelocity(id);physics.velocity(id,v.xMetersPerSecond(),v.yMetersPerSecond()-9.8f/60);continue;
            }
            game.state.getPlayer(id).setBlocking(false);
            if(f.axis!=0) { game.state.getPlayer(id).updateFacing(f.axis); }
            if(input!=null && f.attack==null && tick-f.dodgeStart>=22) {
                if(input.fighter().dodgePressed() && tick>=f.nextDodge && (grounded || !f.airDodgeUsed)) {
                    f.dodgeStart=tick;f.nextDodge=tick+(grounded?60:150);f.airDodgeUsed|=!grounded;
                    double y=input.fighter().upHeld()?1:input.fighter().downHeld()?-1:0,length=Math.hypot(input.moveAxis(),y);
                    f.dodgeX=length==0?0:(float)(input.moveAxis()/length*FighterMovement.DODGE_SPEED);
                    f.dodgeY=length==0?0:(float)(y/length*FighterMovement.DODGE_SPEED);
                } else if(input.fighter().dashPressed() && grounded && tick>=f.nextDash) {
                    f.dashUntil=tick+12;f.nextDash=tick+24;
                    f.dashX=(input.moveAxis()!=0?Math.signum(input.moveAxis()):game.state.getPlayer(id).isFacingRight()?1:-1)*FighterMovement.DASH_SPEED;
                } else {
                    if(input.jumpPressed()) {
                        if(f.held.downHeld() && grounded && physics.dropThrough(id)) { f.jumping=false; }
                        else if(!grounded && wall!=0 && f.wallJumps>0 && tick>=f.nextWallJump) {
                            physics.velocity(id,wall*.6f,4.2f);f.wallJumps--;f.airJumps=1;f.nextWallJump=tick+12;f.jumping=true;
                        } else if(grounded || f.airJumps>0) {
                            physics.velocity(id,velocity.xMetersPerSecond(),4.2f);if(!grounded) { f.airJumps--; }f.jumping=true;
                        }
                    }
                    if(input.fighter().throwPressed()) { game.items.throwWeapon(id,input.moveAxis(),f.held.upHeld()?1:f.held.downHeld()?-1:0,tick); }
                    else { game.items.interact(input,tick); }
                    if(input.meleePressed() || input.fighter().heavyPressed() || input.abilityPressed()) {
                        String move;
                        boolean heavy=input.fighter().heavyPressed() || input.abilityPressed();
                        if(heavy) {
                            if(!grounded && f.held.downHeld()) { move="GROUND_POUND"; }
                            else if(!grounded) { move="RECOVERY"; }
                            else { move=f.held.downHeld()?"DSIG":Math.abs(f.axis)>.1f?"SSIG":"NSIG"; }
                        } else { move=grounded?(f.held.downHeld()?"DLIGHT":Math.abs(f.axis)>.1f?"SLIGHT":"NLIGHT")
                                :(f.held.downHeld()?"DAIR":Math.abs(f.axis)>.1f?"SAIR":"NAIR"); }
                        if(!move.equals("RECOVERY") || f.recovery) {
                            f.attack=new Attack(nextAttackId++,FighterMoves.get(move),game.state.getPlayer(id).isFacingRight()?1:-1,
                                    heavy && grounded && input.fighter().heavyHeld());
                            if(move.equals("RECOVERY")) { f.recovery=false;physics.velocity(id,game.bodies.getVelocity(id).xMetersPerSecond(),4.8f); }
                        }
                    }
                }
            }
            int dodgeAge=(int)(tick-f.dodgeStart);
            if(dodgeAge>=0 && dodgeAge<22) {
                if(dodgeAge>=2 && dodgeAge<17) { physics.velocity(id,f.dodgeX,f.dodgeY); }
                else { gravity(id,false); }
                continue;
            }
            velocity=game.bodies.getVelocity(id);float x=velocity.xMetersPerSecond(),y=velocity.yMetersPerSecond();
            boolean locked=f.attack!=null;
            var movement=FighterMovement.step(x,y,f.axis,f.stats.speed(),grounded,f.held.downHeld(),f.jumping && !f.held.jumpHeld(),!locked && tick>=f.nextWallJump-8);
            x=movement.x();y=movement.y();f.fastFall=movement.fastFalling();
            if(f.dashUntil>tick) { x=f.dashX; }
            if(f.jumping && !f.held.jumpHeld()) { f.jumping=false; }
            f.wallSliding=wall!=0 && !grounded && f.axis*wall<0 && tick>=f.nextWallJump && f.hitstun==0;
            if(f.wallSliding) { x=0;y=Math.max(y,-.55f); }
            physics.velocity(id,x,y);
            game.state.getPlayer(id).updateFacing(f.axis!=0?f.axis:x);
            if(f.attack!=null) {
                var attack=f.attack;
                if(attack.charging) {
                    attack.charge++;
                    if(!f.held.heavyHeld() || attack.charge>=45) { attack.charging=false; }
                } else {
                    attack.frame++;
                    if(attack.move.name().equals("GROUND_POUND") && attack.move.activeAt(attack.frame)) { physics.velocity(id,x,-9.6f); }
                    if(attack.frame>=attack.move.total()) { f.attack=null; }
                }
            }
        }
        // Stable participant order plus per-instance target set makes contact registration reproducible.
        for(var entry:fighters.entrySet()) {
            var id=entry.getKey();var f=entry.getValue();var attack=f.attack;
            if(!game.active(id) || f.hitstun>0 || f.hitstop>0 || attack==null || attack.charging || !attack.move.activeAt(attack.frame)) { continue; }
            var position=game.bodies.getPosition(id);var move=attack.move;float reach=game.progress.get(id).weapon.equals("NONE")?1:1.35f;
            if(!attack.fired) { attack.fired=true;game.items.attack(id,tick,attack.hit); }
            float cx=position.xMeters()+attack.direction*move.x()*reach,cy=position.yMeters()+move.y();
            var targets=physics.targets(cx-move.halfWidth()*reach,cy-move.halfHeight(),cx+move.halfWidth()*reach,cy+move.halfHeight());
            for(var victim:fighters.keySet()) {
                if(victim.equals(id) || !targets.contains(victim) || attack.hit.contains(victim)) { continue; }
                if(hit(id,victim,move.name(),move.damage()*(.75f+f.stats.attack()*.05f)*(1+attack.charge/90f)*reach,
                        move.base(),move.scaling(),attack.direction*(float)Math.cos(Math.toRadians(move.angle())),(float)Math.sin(Math.toRadians(move.angle())),move.heavy())) { attack.hit.add(victim); }
            }
        }
        for(var entry:fighters.entrySet()) {
            var f=entry.getValue();if(f.hitstop>0 && game.active(entry.getKey())) { physics.velocity(entry.getKey(),0,0);f.hitstop--; }
        }
    }
    private void gravity(PlayerId id,boolean fast) { var v=game.bodies.getVelocity(id);physics.velocity(id,v.xMetersPerSecond(),Math.max(fast?-9.6f:-6,v.yMetersPerSecond()-9.8f/60)); }
    boolean projectileHit(PlayerId attacker,PlayerId victim,String weapon,float damage,float x,float y) {
        double length=Math.hypot(x,y);return hit(attacker,victim,weapon,damage,Math.max(.8f,(float)length*.3f),.22f,
                length==0?0:(float)(x/length),length==0?1:(float)(y/length),false);
    }
    boolean hit(PlayerId attacker,PlayerId victim,String weapon,float amount,float base,float scaling,float ux,float uy,boolean heavy) {
        if(!Float.isFinite(amount) || amount<=0 || !Float.isFinite(ux) || !Float.isFinite(uy)) { throw new IllegalArgumentException("Hit"); }
        if(!game.active(victim) || attacker!=null && !game.active(attacker)) { return false; }
        var f=fighters.get(victim);var progress=game.progress.get(victim);long age=tick-f.dodgeStart;
        if(f.platformUntil>0 || tick<progress.invulnerableUntil || age>=2 && age<14) { return false; }
        if(progress.shield) { progress.shield=false;return true; }
        f.damage=Math.min(Double.MAX_VALUE,f.damage+amount);
        double strength=FighterMoves.knockback(f.damage,base,scaling,f.stats.defense());
        double length=Math.hypot(ux,uy);if(length==0) { ux=0;uy=1;length=1; }
        float vx=(float)(strength*ux/length),vy=(float)(strength*uy/length);
        f.frozen=false;f.hitstop=0;
        physics.velocity(victim,vx,vy);f.hitstun=FighterMoves.hitstun(f.damage,scaling);f.tumble=strength>6;f.attack=null;f.dodgeStart=-1000;
        if(attacker!=null && !attacker.equals(victim)) {
            game.progress.get(attacker).damage+=amount;progress.lastAttacker=attacker;progress.lastHitTick=tick;progress.lastWeapon=weapon;
        }
        if(heavy) { freeze(victim);if(attacker!=null) { freeze(attacker); } }
        return true;
    }
    private void freeze(PlayerId id) {
        var f=fighters.get(id);var velocity=game.bodies.getVelocity(id);
        f.frozenX=velocity.xMetersPerSecond();f.frozenY=velocity.yMetersPerSecond();f.frozen=true;
        f.hitstop=2;
    }
    void checkBlastZones() {
        for(var id:fighters.keySet()) {
            if(!game.active(id)) { continue; }var p=game.bodies.getPosition(id);var f=fighters.get(id);
            String edge=p.xMeters()<LEFT?"LEFT":p.xMeters()>RIGHT?"RIGHT":p.yMeters()<BOTTOM?"BOTTOM":p.yMeters()>TOP && f.hitstun>0?"TOP":null;
            if(edge!=null) { game.progress.get(id).koCause="BLAST_"+edge;game.state.getPlayer(id).applyDamage(100);f.attack=null;f.platformUntil=0; }
        }
    }
    void applyLavaContacts() {
        for(var id:fighters.keySet()) {
            if(!game.active(id) || game.jumps.isSupportedBySolid(id)) { continue; }
            var p=game.bodies.getPosition(id);var v=game.bodies.getVelocity(id);var f=fighters.get(id);
            if(f.platformUntil>0 || !FighterArena.touchesLava(p.xMeters(),p.yMeters(),v.yMetersPerSecond())) { continue; }
            physics.bounceFromLava(id);
            f.attack=null;f.dodgeStart=-1000;f.dashUntil=0;f.frozen=false;f.hitstop=0;
            f.hitstun=FighterArena.LAVA_STUN_TICKS;f.tumble=false;f.fastFall=false;f.jumping=false;
            f.airJumps=1;f.wallJumps=3;f.airDodgeUsed=false;f.recovery=true;f.nextWallJump=0;
            if(tick>=f.nextLavaDamage && tick>=game.progress.get(id).invulnerableUntil) {
                var player=game.state.getPlayer(id);player.applyFallPenalty();f.nextLavaDamage=tick+60;
                if(player.isDead()) { game.progress.get(id).fell=true; }
            }
        }
    }
    FighterSnapshot snapshot() {
        var values=new ArrayList<FighterSnapshot.Player>();
        for(var entry:fighters.entrySet()) {
            var id=entry.getKey();var f=entry.getValue();var attack=f.attack;int dodge=(int)Math.max(0,22-(tick-f.dodgeStart));
            String action=!game.active(id)?"KO":f.platformUntil>0?"RESPAWN_PLATFORM":f.hitstun>0?(f.tumble?"TUMBLE":"HITSTUN"):
                    dodge>0?"DODGE":attack==null?"IDLE":attack.charging?"CHARGE":"ATTACK";
            values.add(new FighterSnapshot.Player(id.value(),f.damage,action,attack==null?"NONE":attack.move.name(),attack==null?0:attack.id,
                    attack==null?0:Math.max(0,attack.frame),f.hitstun,dodge,(int)Math.max(0,f.nextDodge-tick),
                    (int)Math.max(0,f.platformUntil-tick),f.fastFall,f.recovery,f.airJumps,f.wallJumps,f.acknowledgedSequence,f.acknowledgedClientTick));
        }
        return new FighterSnapshot(values);
    }
    void heal(PlayerId id) { fighters.get(id).damage=Math.max(0,fighters.get(id).damage-30);game.state.getPlayer(id).heal(30); }
    @Override public void close() { physics.close();fighters.clear(); }
    static final class Fighter {
        final FighterStats stats;double damage;float axis,dodgeX,dodgeY,dashX,frozenX,frozenY;
        FighterInput held=FighterInput.NONE;long lastInput,nextDodge,dodgeStart=-1000,nextDash,dashUntil,platformUntil,nextWallJump,nextLavaDamage;
        long acknowledgedSequence,acknowledgedClientTick;
        int airJumps=1,wallJumps=3,lastWall,hitstun,hitstop;boolean recovery=true,airDodgeUsed,fastFall,wallSliding,jumping,tumble,frozen;
        Attack attack;Fighter(FighterStats stats) { this.stats=stats; }
    }
    private static final class Attack {
        final long id;final FighterMoves.Move move;final int direction;final Set<PlayerId> hit=new HashSet<>();
        int frame=-1,charge;boolean charging,fired;
        Attack(long id,FighterMoves.Move move,int direction,boolean charging) { this.id=id;this.move=move;this.direction=direction;this.charging=charging; }
    }
}
