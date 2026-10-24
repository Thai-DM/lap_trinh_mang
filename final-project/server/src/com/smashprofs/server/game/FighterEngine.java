package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.World;
import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import java.util.*;

/** Simulation-owned fighter combat, including equipment. HP is the sole damage/life model. */
final class FighterEngine implements AutoCloseable {
    static final float LEFT=1,RIGHT=11.5f,BOTTOM=-1.5f,TOP=9;
    final GameplayMatch game;
    final FighterPhysics physics;
    final Map<PlayerId,Fighter> fighters=new LinkedHashMap<>();
    private final float spawnY;
    private final int bufferFrames=UnarmedRules.inputBufferFrames();
    private long tick,nextAttackId=1,hitSerial;
    FighterEngine(GameplayMatch game,World world,MapCollisionDefinition geometry) {
        this.game=game;physics=new FighterPhysics(world,game.bodies,game.jumps,geometry);
        spawnY=1.35f;
        for(var p:game.setup.participants()) { fighters.put(p.playerId(),new Fighter(FighterStats.forCharacter(p.characterId()))); }
    }
    SpawnProvider.SpawnPosition respawnPosition(PlayerId id) {
        var safe=game.safeRandomRespawn(id);
        return new SpawnProvider.SpawnPosition(safe.xMeters(),Math.max(spawnY,safe.yMeters()+.35f));
    }
    void respawn(PlayerId id,long tick) {
        var f=fighters.get(id);f.damage=0;f.attack=null;f.pending=null;f.hitstun=f.hitstop=0;f.frozen=false;
        f.nextDodge=f.nextDash=f.nextWallJump=f.nextLavaDamage=0;f.dodgeStart=-1000;f.dashUntil=0;f.platformUntil=tick+180;
        f.gcUntil=f.chaseUntil=0;f.chaseCharges=0;f.dodgeKind="NONE";f.air.reset();f.wallJumps=3;f.lastWall=f.wallActions=f.runTicks=0;
        f.airDodgeUsed=f.fastFall=f.jumping=f.wallSliding=f.wasGrounded=false;f.recovery=true;f.airJumps=UnarmedRules.AIR_JUMPS;
        f.combo="UNVERIFIED";f.dodgeFrames=-1;f.lastComboAttacker=null;f.hitSerial=0;
        f.lightComboStep=0;f.lastLightAttackTick=-1000;f.nextLightAttackTick=0;f.lastDownPressTick=-1000;f.wasDownHeld=false;
        f.shootingUntil=0;
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
        for(var id:fighters.keySet()) { updatePlayer(id,requests.get(id)); }
        // Every player pose is updated before any hit query; only active-frame hitboxes can register.
        for(var id:fighters.keySet()) {
            var f=fighters.get(id);physics.hurtbox(id,f.attack==null?"NONE":f.attack.move.name(),f.attack==null?0:f.attack.frame);
        }
        for(var id:fighters.keySet()) {
            var f=fighters.get(id);var attack=f.attack;
            if(!game.active(id) || f.hitstun>0 || f.hitstop>0 || attack==null || attack.charging || !attack.move.activeAt(attack.frame)) { continue; }
            var p=game.bodies.getPosition(id);var m=attack.move;
            float rad=(float)Math.toRadians(attack.aimAngle);
            float offsetX=attack.direction*(m.x()*(float)Math.abs(Math.cos(rad)));
            float offsetY=m.y()+(m.x()*(float)Math.sin(rad));
            var targets=physics.targets(p.xMeters()+offsetX-m.halfWidth(),p.yMeters()+offsetY-m.halfHeight(),
                    p.xMeters()+offsetX+m.halfWidth(),p.yMeters()+offsetY+m.halfHeight());
            if(game.boss!=null && !attack.bossHit && game.boss.overlaps(p.xMeters()+offsetX-m.halfWidth(),p.yMeters()+offsetY-m.halfHeight(),p.xMeters()+offsetX+m.halfWidth(),p.yMeters()+offsetY+m.halfHeight())) {
                attack.bossHit=game.boss.damage(id,m.damage()*(.75f+f.stats.attack()*.05f)*(1+attack.charge/90f));
            }
            for(var victim:fighters.keySet()) {
                if(victim.equals(id) || !targets.contains(victim) || attack.hit.contains(victim)) { continue; }
                if(hit(id,victim,m.name(),m.damage()*(.75f+f.stats.attack()*.05f)*(1+attack.charge/90f),m.base(),m.scaling(),
                        attack.direction*(float)Math.cos(rad),(float)Math.sin(rad),m.heavy())) { attack.hit.add(victim); }
            }
        }
        for(var id:fighters.keySet()) {
            var f=fighters.get(id);f.airJumps=f.air.jumps();f.recovery=f.air.canRecover();f.wallJumps=Math.min(3,Math.max(0,UnarmedRules.WALL_SLIP-f.wallActions));
            if(f.hitstop>0 && game.active(id)) { physics.velocity(id,0,0);f.hitstop--; }
        }
    }
    private void updatePlayer(PlayerId id,PlayerInput input) {
        var f=fighters.get(id);
        if(!game.active(id)) { f.attack=null;f.pending=null;f.fastFall=false;return; }
        if(f.pending!=null && tick>f.pending.expires) { f.pending=null; }
        if(tick>f.chaseUntil) { f.chaseCharges=0; }
        if(tick-f.lastInput>=15) { f.axis=0;f.held=FighterInput.NONE; }
        if(f.platformUntil>0) {
            boolean leave=tick>=f.platformUntil || input!=null && (input.moveAxis()!=0 || UnarmedInputResolver.hasAction(input) || input.abilityPressed() || input.interactPressed() || input.dropPressed() || !input.fighter().equals(FighterInput.NONE));
            if(!leave) { physics.velocity(id,0,0);return; }
            f.platformUntil=0;f.pending=null;game.progress.get(id).invulnerableUntil=tick+120;
        }
        if(f.hitstop>0) { return; }
        if(f.frozen) { physics.velocity(id,f.frozenX,f.frozenY);f.frozen=false; }
        boolean grounded=game.jumps.getJumpState(id).grounded();int wall=game.jumps.wallDirection(id);
        if(grounded) {
            f.air.reset();f.wallJumps=3;f.airDodgeUsed=false;f.jumping=false;f.wallActions=0;
            if(!f.wasGrounded && f.nextDodge>tick+UnarmedRules.GROUND_COOLDOWN) { f.nextDodge=tick+UnarmedRules.GROUND_COOLDOWN; }
        } else if(wall!=0 && f.lastWall==0 && f.wallActions<UnarmedRules.WALL_SLIP) { f.air.reset(); }
        f.wasGrounded=grounded;f.lastWall=wall;
        if(f.hitstun>0) { f.hitstun--;f.attack=null;f.pending=null;gravity(id,false);return; }
        game.state.getPlayer(id).setBlocking(false);
        boolean attackEdge=input!=null && (input.meleePressed() || input.fighter().heavyPressed());
        boolean dodging=dodgeRemaining(f)>0;
        boolean gc=attackEdge && !grounded && f.dodgeKind.equals("AIR_SPOT") && tick<=f.gcUntil && dodging;
        boolean chaseCancel=attackEdge && f.dodgeKind.equals("CHASE") && dodging;
        if(gc || chaseCancel) { f.dodgeStart=-1000;f.gcUntil=0;dodging=false; }
        if(f.attack!=null && UnarmedInputResolver.hasAction(input) && !f.attack.charging
                && f.attack.move.phase(f.attack.frame).equals("RECOVERY") && f.attack.move.total(!f.attack.hit.isEmpty())-1-f.attack.frame<=bufferFrames) {
            f.pending=new Pending(input,tick+bufferFrames);
        }
        boolean hasWeapon=!game.progress.get(id).weapon.equals("NONE");
        boolean attackIntent=input!=null && (input.meleePressed() || input.fighter().heavyPressed() || (hasWeapon && input.blockHeld()));
        boolean shooting=hasWeapon && (attackIntent || tick<f.shootingUntil);
        if(f.attack==null && !dodging) {
            if(!UnarmedInputResolver.hasAction(input) && f.pending!=null) {
                input=f.pending.input;f.pending=null;
                attackIntent=input!=null && (input.meleePressed() || input.fighter().heavyPressed() || (hasWeapon && input.blockHeld()));
                shooting=hasWeapon && (attackIntent || tick<f.shootingUntil);
            }
            boolean usedDodge=false;
            if(!shooting && input!=null && (input.fighter().dodgePressed() || input.fighter().dashPressed())) {
                usedDodge=movementAction(id,f,input,grounded);
            }
            if(!usedDodge && input!=null) {
                boolean downNow=f.held.downHeld();boolean doubleTapDown=false;
                if(downNow) {
                    if(!f.wasDownHeld) {
                        if(tick-f.lastDownPressTick<=22) { doubleTapDown=true; }
                        f.lastDownPressTick=tick;
                    }
                    f.wasDownHeld=true;
                } else { f.wasDownHeld=false; }
                boolean dropIntent=!attackIntent && (input.dropPressed() || doubleTapDown);
                if(grounded && dropIntent && physics.dropThrough(id)) {
                    grounded=false;f.jumping=false;
                } else if(!shooting && input.jumpPressed()) {
                    var v=game.bodies.getVelocity(id);
                    if(grounded && f.held.downHeld() && physics.dropThrough(id)) { grounded=false;f.jumping=false; }
                    else if(!grounded && wall!=0 && f.wallActions<UnarmedRules.WALL_SLIP && tick>=f.nextWallJump) {
                        physics.velocity(id,wall*.6f,4.2f);f.air.reset();f.nextWallJump=tick+12;f.jumping=true;f.wallActions++;
                    } else if(grounded || f.air.jump()) {
                        physics.velocity(id,v.xMetersPerSecond(),4.2f);f.jumping=true;if(!grounded) { f.wallActions=Math.min(99,f.wallActions+1); }grounded=false;
                    }
                }
                if(input.fighter().throwPressed()) { game.items.throwWeapon(id,input.moveAxis(),f.held.upHeld()?1:f.held.downHeld()?-1:0,tick); }
                else {
                    game.items.interact(input,tick);
                    if(attackIntent) { startAttack(id,f,input,grounded || gc,gc); }
                }
            }
        } else if(f.dodgeKind.equals("CHASE") && dodging && f.chaseCharges>0 && tick<=f.chaseUntil && input!=null && (input.fighter().dodgePressed() || input.fighter().dashPressed())) {
            movementAction(id,f,input,grounded);
        }
        if(dodgeRemaining(f)>0) {
            int age=(int)(tick-f.dodgeStart);boolean spot=f.dodgeKind.endsWith("SPOT");
            if(f.dodgeKind.equals("GROUND_SPOT") && age>=FighterMovement.DASH_TICKS) {
                // Ground dodge keeps its protection/recovery, but must not slide throughout all those phases.
                physics.velocity(id,0,0);
            } else if(f.dodgeKind.startsWith("AIR_") && age>=FighterMovement.AIR_DODGE_TICKS) {
                var v=game.bodies.getVelocity(id);physics.velocity(id,0,v.yMetersPerSecond()-9.8f/60);
            } else if(spot || age<UnarmedRules.DODGE_STARTUP+UnarmedRules.invulnerableFrames(f.dodgeKind)) {
                physics.velocity(id,f.dodgeX,f.dodgeY);
                if(f.dodgeX!=0) { game.state.getPlayer(id).updateFacing(f.dodgeX); }
            } else { gravity(id,false); }
            return;
        }
        var v=game.bodies.getVelocity(id);boolean locked=f.attack!=null;
        if(f.attack!=null && f.attack.gravityCancelled) { physics.velocity(id,0,0); }
        else {
            float effectiveAxis=shooting?0:f.axis;
            f.runTicks=grounded && effectiveAxis!=0 && !locked?Math.min(18,f.runTicks+1):0;
            var move=FighterMovement.step(v.xMetersPerSecond(),v.yMetersPerSecond(),effectiveAxis,f.stats.speed(),grounded,f.held.downHeld() && !locked && !shooting,
                    f.jumping && !f.held.jumpHeld(),!locked && tick>=f.nextWallJump-8,f.runTicks>=18);
            float x=f.dashUntil>tick && !shooting?f.dashX:(shooting?0:move.x()),y=move.y();f.fastFall=move.fastFalling();
            if(f.jumping && !f.held.jumpHeld()) { f.jumping=false; }
            f.wallSliding=wall!=0 && !grounded && effectiveAxis*wall<0 && tick>=f.nextWallJump;
            if(f.wallSliding) { x=0;y=Math.max(y,f.wallActions>=UnarmedRules.WALL_SLIP?-1.5f:-.55f); }
            physics.velocity(id,x,y);
            if(shooting) {
                f.dashUntil=0;
                float aimX=input!=null?input.moveAxis():0;
                if(aimX!=0) { game.state.getPlayer(id).updateFacing(aimX); }
            } else if(!(f.dashUntil>tick && f.backdash)) { game.state.getPlayer(id).updateFacing(f.axis!=0?f.axis:x); }
        }
        if(f.attack!=null) {
            var attack=f.attack;
            if(attack.charging) {
                attack.charge++;
                if(attack.charge>=attack.move.maxCharge() || attack.charge>=attack.move.minCharge() && !f.held.heavyHeld()) { attack.charging=false; }
            } else {
                attack.frame++;
                if(attack.move.name().equals("GROUND_POUND") && attack.move.activeAt(attack.frame)) { physics.velocity(id,game.bodies.getVelocity(id).xMetersPerSecond(),-9.6f); }
                if(attack.frame>=attack.move.total(!attack.hit.isEmpty())) { f.attack=null; }
            }
        }
    }
    private void startAttack(PlayerId id,Fighter f,PlayerInput input,boolean grounded,boolean gc) {
        // Decide from equipment BEFORE consuming ammo: the last shot must never also become a punch.
        if(!game.progress.get(id).weapon.equals("NONE")) {
            float aimX=input.moveAxis();
            float aimY=(input.fighter().upHeld()?1f:0f)-(input.fighter().downHeld()?1f:0f);
            if(aimX!=0) { game.state.getPlayer(id).updateFacing(aimX); }
            game.items.attack(id,aimX,aimY,tick);f.shootingUntil=tick+10;f.pending=null;return;
        }
        boolean heavy=input.fighter().heavyPressed();
        boolean up=input.fighter().upHeld();
        boolean down=input.fighter().downHeld();
        float h=input.moveAxis();
        var direction=UnarmedInputResolver.direction(h,down,up,true);
        String name=UnarmedInputResolver.move(grounded,heavy,direction);float recoverySpeed=0;
        if(grounded && !heavy && !gc && name.equals("NLIGHT")) {
            if(tick<f.nextLightAttackTick) { return; }
            if(f.lightComboStep==1 && tick>f.lastLightAttackTick && tick-f.lastLightAttackTick<=45) {
                f.lightComboStep=2;name="NLIGHT2";
            } else if(f.lightComboStep==2 && tick>f.lastLightAttackTick && tick-f.lastLightAttackTick<=45) {
                f.lightComboStep=0;name="NLIGHT3";f.nextLightAttackTick=tick+10;
            } else {
                f.lightComboStep=1;name="NLIGHT";
            }
            f.lastLightAttackTick=tick;
        } else {
            f.lightComboStep=0;
        }
        if(name.equals("RECOVERY")) { recoverySpeed=f.air.recover();if(recoverySpeed==0) { return; }f.wallActions=Math.min(99,f.wallActions+1); }
        int facing=game.state.getPlayer(id).isFacingRight()?1:-1;
        int attackDirection=direction==UnarmedInputResolver.Direction.SIDE?(input.moveAxis()<0?-1:1):facing;
        var move=FighterMoves.get(name);
        float aimAngle;
        if(up && h!=0) { aimAngle=45f; }
        else if(down && h!=0) { aimAngle=-45f; }
        else if(up) { aimAngle=85f; }
        else if(down) { aimAngle=-85f; }
        else { aimAngle=move.angle(); }
        f.attack=new Attack(nextAttackId++,move,attackDirection,move.chargeable(),gc,aimAngle);f.pending=null;
        if(recoverySpeed>0) { physics.velocity(id,game.bodies.getVelocity(id).xMetersPerSecond(),recoverySpeed);f.jumping=false; }
        if(gc) { physics.velocity(id,0,0); }
        else if(grounded && (name.startsWith("NLIGHT") || name.equals("SLIGHT"))) {
            float stepSpeed=name.equals("NLIGHT3")?1.15f:0.90f;
            physics.velocity(id,attackDirection*stepSpeed,game.bodies.getVelocity(id).yMetersPerSecond());
        }
    }
    private boolean movementAction(PlayerId id,Fighter f,PlayerInput input,boolean grounded) {
        float y=input.fighter().upHeld()?1:input.fighter().downHeld()?-1:0,x=input.moveAxis();double length=Math.hypot(x,y);
        boolean chase=f.chaseCharges>0 && tick<=f.chaseUntil && length>0 && !(grounded && y<0 && x!=0);
        if(chase && f.chaseTarget!=null && game.active(f.chaseTarget)) {
            float dx=game.bodies.getPosition(f.chaseTarget).xMeters()-game.bodies.getPosition(id).xMeters();
            chase=x==0 && y!=0 || x!=0 && Math.signum(x)==Math.signum(dx);
        } else { chase=false; }
        if(chase) {
            f.chaseCharges--;f.dodgeKind="CHASE";f.dodgeStart=tick;f.gcUntil=0;
            f.dodgeX=(float)(x/length*UnarmedRules.CHASE_SPEED);f.dodgeY=(float)(y/length*UnarmedRules.CHASE_SPEED);return true;
        }
        if(tick<f.nextDash || tick<f.nextDodge) { return false; }
        if(grounded && input.fighter().dashPressed()) {
            int facing=game.state.getPlayer(id).isFacingRight()?1:-1;
            int direction=x!=0?(x<0?-1:1):facing;
            boolean backdash=direction!=facing && (input.jumpPressed() || input.fighter().jumpHeld());
            f.backdash=backdash;f.dashX=direction*FighterMovement.DASH_SPEED;
            f.dashUntil=tick+FighterMovement.DASH_TICKS;f.nextDash=tick+FighterMovement.DASH_COOLDOWN_TICKS;
            if(!backdash) { game.state.getPlayer(id).updateFacing(direction); }
            return false;
        }
        if(!grounded && f.airDodgeUsed) { return false; }
        f.dodgeKind=grounded?"GROUND_SPOT":length==0?"AIR_SPOT":"AIR_DIRECTION";f.dodgeStart=tick;
        f.nextDodge=tick+(grounded?UnarmedRules.GROUND_COOLDOWN:UnarmedRules.AIR_COOLDOWN);f.airDodgeUsed|=!grounded;
        f.gcUntil=f.dodgeKind.equals("AIR_SPOT")?tick+UnarmedRules.GC_WINDOW:0;
        int facing=game.state.getPlayer(id).isFacingRight()?1:-1;
        if(grounded) {
            int direction=x!=0?(x<0?-1:1):facing;
            f.dodgeX=direction*FighterMovement.DODGE_SPEED;
            f.dodgeY=0;
            f.dashUntil=tick+FighterMovement.DASH_TICKS;
            f.dashX=direction*FighterMovement.DASH_SPEED;
        } else {
            float airX=length==0?facing:(float)(x/length);
            float airY=length==0?0:(float)(y/length);
            f.dodgeX=airX*FighterMovement.AIR_DODGE_SPEED;
            f.dodgeY=airY*FighterMovement.AIR_DODGE_SPEED;
            f.dashUntil=0;
            f.dashX=0;
            if(airX!=0) { game.state.getPlayer(id).updateFacing(airX<0?-1:1); }
        }
        return true;
    }
    private int dodgeRemaining(Fighter f) { return (int)Math.max(0,UnarmedRules.totalDodgeFrames(f.dodgeKind)-(tick-f.dodgeStart)); }
    private void gravity(PlayerId id,boolean ignored) { var v=game.bodies.getVelocity(id);physics.velocity(id,v.xMetersPerSecond(),v.yMetersPerSecond()-9.8f/60); }
    void stun(PlayerId id,int ticks) {
        if(!game.active(id)) { return; }
        var f=fighters.get(id);
        if(f!=null) {
            f.hitstun=Math.max(f.hitstun,ticks);
            f.tumble=true;
            f.attack=null;
            f.pending=null;
        }
    }
    boolean projectileHit(PlayerId attacker,PlayerId victim,String weapon,float damage,float x,float y) {
        double length=Math.hypot(x,y);return hit(attacker,victim,weapon,damage,Math.max(.8f,(float)length*.3f),.22f,length==0?0:(float)(x/length),length==0?1:(float)(y/length),false);
    }
    boolean hit(PlayerId attacker,PlayerId victim,String moveName,float amount,float base,float scaling,float ux,float uy,boolean heavy) {
        if(!Float.isFinite(amount) || amount<=0 || !Float.isFinite(ux) || !Float.isFinite(uy)) { throw new IllegalArgumentException("Hit"); }
        if(game.boss!=null && attacker!=null) { return false; }
        if(!game.active(victim) || attacker!=null && !game.active(attacker)) { return false; }
        var f=fighters.get(victim);var progress=game.progress.get(victim);long age=tick-f.dodgeStart;
        if(f.platformUntil>0 || tick<progress.invulnerableUntil || age>=UnarmedRules.DODGE_STARTUP && age<UnarmedRules.DODGE_STARTUP+UnarmedRules.invulnerableFrames(f.dodgeKind)) { return false; }
        if(progress.shield) { progress.shield=false;return true; }
        var player=game.state.getPlayer(victim);float before=player.getHp();player.applyDamage(amount);f.damage=100-player.getHp();
        if(f.lastComboAttacker!=null && f.lastComboAttacker.equals(attacker) && tick-f.lastHitTick<=90) {
            f.dodgeFrames=(int)Math.min(999,Math.max(0,tick-f.lastDodgeReadyTick+1));
            f.combo=f.lastDodgeUnavailable?"DODGE_UNAVAILABLE":f.dodgeFrames==0?"ZERO_WINDOW":"STRING";
        } else { f.dodgeFrames=-1;f.combo="UNVERIFIED"; }
        double strength=FighterMoves.knockback(f.damage,base,scaling,f.stats.defense())*(heavy?0.5:1.0);double length=Math.hypot(ux,uy);
        if(length==0) { ux=0;uy=1;length=1; }
        float vx=(float)(strength*ux/length),vy=(float)(strength*uy/length);f.frozen=false;f.hitstop=0;
        int stun;try { stun=FighterMoves.get(moveName).baseStun()+(int)Math.ceil(f.damage*scaling/10); }catch(IllegalArgumentException unknown) { stun=FighterMoves.hitstun(f.damage,scaling); }
        f.lastDodgeUnavailable=f.airDodgeUsed && !game.jumps.getJumpState(victim).grounded() || f.nextDodge>tick+stun+1;
        // Count opportunities to INPUT a dodge, not only frames where its startup has finished.
        f.lastDodgeReadyTick=tick+Math.min(45,stun)+1+(heavy?UnarmedRules.HEAVY_HITSTOP:0);f.lastHitTick=tick;f.lastComboAttacker=attacker;f.hitSerial=++hitSerial;
        boolean isLightHold=attacker!=null && (moveName.startsWith("NLIGHT") && !moveName.equals("NLIGHT3") || moveName.equals("SLIGHT"));
        if(isLightHold) {
            float attackerX=game.bodies.getPosition(attacker).xMeters();
            float facing=game.state.getPlayer(attacker).isFacingRight()?1f:-1f;
            float targetX=attackerX+facing*0.15f;
            float currentVictimX=game.bodies.getPosition(victim).xMeters();
            float pullVx=(targetX-currentVictimX)*6.0f;
            physics.velocity(victim,Math.max(-1.5f,Math.min(1.5f,pullVx)),0);
            f.tumble=false;
        } else {
            physics.velocity(victim,vx,vy);f.tumble=moveName.equals("NLIGHT3") || heavy || strength>6;
        }
        f.hitstun=Math.min(45,stun);
        f.attack=null;f.pending=null;f.dodgeStart=-1000;f.gcUntil=0;f.dashUntil=0;f.wallActions=0;
        if(player.isDead()) { progress.koCause="HP_ZERO"; }
        if(attacker!=null && !attacker.equals(victim)) {
            game.progress.get(attacker).damage+=before-player.getHp();progress.lastAttacker=attacker;progress.lastHitTick=tick;progress.lastWeapon=moveName;
            var a=fighters.get(attacker);a.chaseCharges=UnarmedRules.CHASE_CHARGES;a.chaseUntil=tick+UnarmedRules.CHASE_WINDOW;a.chaseTarget=victim;a.wallActions=0;
        }
        if(heavy) { freeze(victim);if(attacker!=null) { freeze(attacker); } }return true;
    }
    private void freeze(PlayerId id) {
        var f=fighters.get(id);var v=game.bodies.getVelocity(id);f.frozenX=v.xMetersPerSecond();f.frozenY=v.yMetersPerSecond();f.frozen=true;f.hitstop=UnarmedRules.HEAVY_HITSTOP;
    }
    void checkBlastZones() {
        for(var id:fighters.keySet()) {
            if(!game.active(id)) { continue; }var p=game.bodies.getPosition(id);var f=fighters.get(id);
            String edge=p.xMeters()<LEFT?"LEFT":p.xMeters()>RIGHT?"RIGHT":p.yMeters()<BOTTOM?"BOTTOM":p.yMeters()>TOP && f.hitstun>0?"TOP":null;
            if(edge!=null) { game.progress.get(id).koCause="BLAST_"+edge;game.state.getPlayer(id).applyDamage(100);f.attack=null;f.pending=null;f.platformUntil=0; }
        }
    }
    void applyLavaContacts() {
        for(var id:fighters.keySet()) {
            if(!game.active(id) || game.jumps.isSupportedBySolid(id)) { continue; }
            var p=game.bodies.getPosition(id);var v=game.bodies.getVelocity(id);var f=fighters.get(id);
            if(f.platformUntil>0 || !FighterArena.touchesLava(p.xMeters(),p.yMeters(),v.yMetersPerSecond())) { continue; }
            physics.bounceFromLava(id);f.attack=null;f.pending=null;f.dodgeStart=-1000;f.gcUntil=0;f.chaseCharges=0;f.dashUntil=0;f.frozen=false;f.hitstop=0;
            f.hitstun=FighterArena.LAVA_STUN_TICKS;f.tumble=f.fastFall=f.jumping=false;f.air.reset();f.airJumps=UnarmedRules.AIR_JUMPS;f.wallJumps=3;f.airDodgeUsed=false;f.recovery=true;f.nextWallJump=0;
            if(tick>=f.nextLavaDamage && tick>=game.progress.get(id).invulnerableUntil) {
                var player=game.state.getPlayer(id);player.applyFallPenalty();f.nextLavaDamage=tick+60;f.damage=100-player.getHp();if(player.isDead()) { game.progress.get(id).fell=true; }
            }
        }
    }
    FighterSnapshot snapshot() {
        var values=new ArrayList<FighterSnapshot.Player>();
        for(var id:fighters.keySet()) {
            var f=fighters.get(id);var a=f.attack;int dodge=dodgeRemaining(f);
            String action=!game.active(id)?"KO":f.platformUntil>0?"RESPAWN_PLATFORM":f.hitstun>0?(f.tumble?"TUMBLE":"HITSTUN"):dodge>0?"DODGE":a==null?"IDLE":a.charging?"CHARGE":"ATTACK";
            var velocity=game.bodies.getVelocity(id);
            String state=action.equals("IDLE")?(f.dashUntil>tick?(f.backdash?"BACKDASH":"DASH"):f.wallSliding?(f.wallActions>=UnarmedRules.WALL_SLIP?"WALL_SLIP":"WALL_SLIDE"):
                    !game.jumps.getJumpState(id).grounded()?(f.fastFall?"FAST_FALL":velocity.yMetersPerSecond()>0?"AIR_RISING":"AIR_FALLING"):f.axis!=0?(f.runTicks>=18?"SPRINT":"RUN"):"IDLE"):
                    action.equals("DODGE") && f.dodgeKind.equals("CHASE")?"CHASE_DODGE":a!=null && a.gravityCancelled?"GRAVITY_CANCEL":action;
            String phase=a==null?(dodge>0?(tick-f.dodgeStart<UnarmedRules.DODGE_STARTUP?"STARTUP":tick-f.dodgeStart<UnarmedRules.DODGE_STARTUP+UnarmedRules.invulnerableFrames(f.dodgeKind)?"ACTIVE":"RECOVERY"):"NONE"):a.charging?"CHARGE":a.move.phase(a.frame);
            var detail=new FighterSnapshot.Detail(state,phase,dodge>0?f.dodgeKind:"NONE",f.air.recoveries(),f.air.used(),(int)Math.max(0,f.gcUntil-tick),f.chaseCharges,(int)Math.max(0,f.chaseUntil-tick),
                    f.pending==null?0:(int)Math.max(0,f.pending.expires-tick),a!=null && a.gravityCancelled,f.wallActions,f.dodgeFrames,f.combo,f.hitSerial,(int)Math.max(0,f.dashUntil-tick),f.dashX,(int)Math.max(0,f.nextWallJump-tick),f.runTicks,(int)Math.max(0,f.nextDash-tick),a==null?(game.state.getPlayer(id).isFacingRight()?1:-1):a.direction);
            values.add(new FighterSnapshot.Player(id.value(),0,action,a==null?"NONE":a.move.name(),a==null?0:a.id,a==null?0:Math.max(0,a.frame),f.hitstun,dodge,(int)Math.max(0,f.nextDodge-tick),
                    (int)Math.max(0,f.platformUntil-tick),f.fastFall,f.air.canRecover(),f.air.jumps(),f.wallJumps,f.acknowledgedSequence,f.acknowledgedClientTick,detail));
        }
        return new FighterSnapshot(values);
    }
    void heal(PlayerId id) { game.state.getPlayer(id).heal(30);fighters.get(id).damage=100-game.state.getPlayer(id).getHp(); }
    @Override public void close() { physics.close();fighters.clear(); }
    static final class Fighter {
        final FighterStats stats;final AirActionBudget air=new AirActionBudget();double damage;float axis,dodgeX,dodgeY,dashX,frozenX,frozenY;
        FighterInput held=FighterInput.NONE;long lastInput,nextDodge,dodgeStart=-1000,nextDash,dashUntil,platformUntil,nextWallJump,nextLavaDamage,gcUntil,chaseUntil;
        long acknowledgedSequence,acknowledgedClientTick,lastHitTick,lastDodgeReadyTick,hitSerial;
        int airJumps=UnarmedRules.AIR_JUMPS,wallJumps=3,lastWall,wallActions,hitstun,hitstop,runTicks,chaseCharges,dodgeFrames=-1,lightComboStep;
        boolean recovery=true,airDodgeUsed,fastFall,wallSliding,jumping,tumble,frozen,wasGrounded,backdash,lastDodgeUnavailable;
        long lastLightAttackTick=-1000,nextLightAttackTick,lastDownPressTick=-1000,shootingUntil;
        boolean wasDownHeld;
        String dodgeKind="NONE",combo="UNVERIFIED";PlayerId chaseTarget,lastComboAttacker;Pending pending;
        Attack attack;Fighter(FighterStats stats) { this.stats=stats; }
    }
    private record Pending(PlayerInput input,long expires) { }
    static final class Attack {
        final long id;final FighterMoves.Move move;final int direction;final Set<PlayerId> hit=new HashSet<>();final boolean gravityCancelled;
        final float aimAngle;
        int frame=-1,charge;boolean charging,bossHit;
        Attack(long id,FighterMoves.Move move,int direction,boolean charging,boolean gc,float aimAngle) {
            this.id=id;this.move=move;this.direction=direction;this.charging=charging;this.gravityCancelled=gc;this.aimAngle=aimAngle;
        }
    }
}
