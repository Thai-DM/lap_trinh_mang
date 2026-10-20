package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import java.util.*;

/** One simulation-owned extension per negotiated stock match; no sockets, timers or disk I/O. */
final class GameplayMatch implements AutoCloseable {
    static final int RESPAWN_TICKS=90, INVULNERABLE_TICKS=120, ULTIMATE_TICKS=600, ATTRIBUTION_TICKS=300;
    final MatchSetup setup;
    final MatchState state;
    final PlayerPhysicsFactory.PlayerPhysicsBodies bodies;
    final PlayerJumpController jumps;
    final Map<PlayerId,Progress> progress=new LinkedHashMap<>();
    final ItemSystem items;
    FighterEngine fighter;
    private final List<MatchEvent> events=new ArrayList<>(128);
    private final Set<PlayerId> disconnected=new HashSet<>();
    private final List<SpawnProvider.SpawnPosition> respawnCandidates;
    private final Map<PlayerId,SpawnProvider.SpawnPosition> lastRespawn=new HashMap<>();
    private final Random respawnRandom;
    private final List<MapCollisionDefinition.CollisionRectangle> lavaZones;
    private long tick;
    private GameplaySnapshot frozen;
    private final KnockbackScaling knockback=KnockbackScaling.configured();
    GameplayMatch(MatchSetup setup,MatchState state,PlayerPhysicsFactory.PlayerPhysicsBodies bodies,
                  PlayerJumpController jumps,MapCollisionDefinition geometry) {
        this.setup=setup;this.state=state;this.bodies=bodies;this.jumps=jumps;
        lavaZones=geometry.lavaZones();
        for(var p:setup.participants()) { progress.put(p.playerId(),new Progress(setup.rules().lives())); }
        respawnCandidates=new SpawnProvider(geometry,0.01f).getRespawnCandidates();
        respawnRandom=new Random(setup.matchId().hashCode()^0x51AFEEDL);
        items=new ItemSystem(this,geometry,new Random(setup.matchId().hashCode()));
        jumps.enableWallMovement();
    }
    synchronized void disconnect(PlayerId id) { if(progress.containsKey(id)) { disconnected.add(id); } }
    void enableFighter(com.badlogic.gdx.physics.box2d.World world,MapCollisionDefinition geometry) { fighter=new FighterEngine(this,world,geometry); }
    void beforeTick(long tick) {
        this.tick=tick;
        synchronized(this) {
            for(var id:disconnected) {
                var p=progress.get(id); if(p.lives==0) { continue; }
                if(!state.getPlayer(id).isDead()) { state.getPlayer(id).applyDamage(100); }
                p.lives=0;p.respawnAt=-1;p.invulnerableUntil=0;p.deaths++;p.weapon="NONE";p.ammo=0;p.shield=false;
                bodies.setParticipating(id,false);event("PLAYER_ELIMINATED",null,id,"NONE","DISCONNECT");
            }
            disconnected.clear();
        }
        for(var entry:progress.entrySet()) {
            var id=entry.getKey();var p=entry.getValue();
            if(p.lives>0 && p.respawnAt>0 && tick>=p.respawnAt) {
                state.getPlayer(id).respawn();p.respawnAt=0;p.invulnerableUntil=tick+INVULNERABLE_TICKS;p.lastAttacker=null;
                var position=fighter==null?safeRandomRespawn(id):fighter.respawnPosition(id);lastRespawn.put(id,position);bodies.respawn(id,position.xMeters(),position.yMeters());
                if(fighter!=null) { fighter.respawn(id,tick); }
                jumps.resetAfterRespawn(id);event("PLAYER_RESPAWNED",null,id,"NONE","RESPAWN");
            }
            // Attribution expires by simulation time, not client timestamps.
            if(p.lastAttacker!=null && tick-p.lastHitTick>ATTRIBUTION_TICKS) { p.lastAttacker=null; }
        }
    }
    boolean active(PlayerId id) { var p=progress.get(id);return p!=null && p.lives>0 && p.respawnAt==0 && !state.getPlayer(id).isDead(); }
    SpawnProvider.SpawnPosition safeRandomRespawn(PlayerId id) {
        var scores=new double[respawnCandidates.size()];double best=-1;
        for(int i=0;i<scores.length;i++) {
            var candidate=respawnCandidates.get(i);double distance=Double.POSITIVE_INFINITY;
            for(var other:progress.keySet()) {
                if(other.equals(id) || !active(other)) { continue; }
                var position=bodies.getPosition(other);double dx=candidate.xMeters()-position.xMeters(),dy=candidate.yMeters()-position.yMeters();
                distance=Math.min(distance,dx*dx+dy*dy);
            }
            if(candidate.equals(lastRespawn.get(id)) && scores.length>1) { distance=-1; }
            scores[i]=distance;best=Math.max(best,distance);
        }
        var choices=new ArrayList<SpawnProvider.SpawnPosition>();
        // Randomize across the safer half of the arena; never prefer a nearby camper.
        double threshold=Double.isInfinite(best)?best:best*.5;
        for(int i=0;i<scores.length;i++) { if(scores[i]>=threshold) { choices.add(respawnCandidates.get(i)); } }
        return choices.get(respawnRandom.nextInt(choices.size()));
    }
    boolean ultimateReady(PlayerId id) { return active(id) && tick>=progress.get(id).nextUltimate; }
    void ultimateUsed(PlayerId id) { progress.get(id).nextUltimate=tick+ULTIMATE_TICKS; }
    boolean damage(PlayerId attacker,PlayerId target,String weapon,float amount,float x,float y) {
        if(fighter!=null) { return fighter.projectileHit(attacker,target,weapon,amount,x,y); }
        if(!Float.isFinite(amount) || amount<=0 || !Float.isFinite(x) || !Float.isFinite(y)) { throw new IllegalArgumentException("Invalid hit"); }
        if(!active(target) || (attacker!=null && !active(attacker))) { return false; }
        var p=progress.get(target);var victim=state.getPlayer(target);
        if(tick<p.invulnerableUntil || victim.isBlocking()) { return false; }
        if(p.shield) { p.shield=false;return false; }
        float before=victim.getHp();victim.applyDamage(amount);
        if(attacker!=null && !attacker.equals(target)) {
            progress.get(attacker).damage+=before-victim.getHp();p.lastAttacker=attacker;p.lastHitTick=tick;p.lastWeapon=weapon;
        }
        var impulse=knockback.scale(victim.getHp(),x,y);
        jumps.applyProjectileKnockback(target,impulse.x(),impulse.y());
        return true;
    }
    void fell(PlayerId id) {
        if(!active(id)) { return; }
        progress.get(id).fell=true;state.getPlayer(id).applyDamage(100);
    }
    /** Contact with visible lava rebounds the body and charges 10 HP at most once per second. */
    void applyLavaContacts() {
        float radius=PlayerPhysicsFactory.PLAYER_RADIUS_METERS;
        for(var id:progress.keySet()) {
            if(!active(id) || jumps.isSupportedBySolid(id)) { continue; }var position=bodies.getPosition(id);boolean touching=position.yMeters()<0;
            if(position.xMeters()>=4.98f && position.xMeters()<=6.38f && position.yMeters()>=0.14f) { continue; }
            for(var lava:lavaZones) {
                float left=lava.xPixels()/100,right=(lava.xPixels()+lava.widthPixels())/100,top=(lava.yPixels()+lava.heightPixels())/100-.08f;
                if(position.xMeters()+radius>=left && position.xMeters()-radius<=right && position.yMeters()-radius<=top+.002f) { touching=true;break; }
            }
            if(!touching) { continue; }
            var p=progress.get(id);jumps.bounceFromLava(id);
            if(tick<p.nextLavaDamage || tick<p.invulnerableUntil) { continue; }
            p.nextLavaDamage=tick+60;state.getPlayer(id).applyFallPenalty();
            if(state.getPlayer(id).isDead()) { p.fell=true; }
        }
    }
    void afterTick() {
        for(var entry:progress.entrySet()) {
            var id=entry.getKey();var p=entry.getValue();
            if(!state.getPlayer(id).isDead() || p.respawnAt!=0 || p.lives==0) { continue; }
            p.lives--;p.deaths++;p.weapon="NONE";p.ammo=0;p.shield=false;p.invulnerableUntil=0;
            var killer=p.lastAttacker!=null && tick-p.lastHitTick<=ATTRIBUTION_TICKS?p.lastAttacker:null;
            if(killer!=null && !killer.equals(id)) { progress.get(killer).kills++; }
            if(p.fell) { event("PLAYER_FELL",killer,id,p.lastWeapon,"LAVA"); }
            else { event("PLAYER_KILLED",killer,id,p.lastWeapon,fighter==null?"HP_ZERO":p.koCause); }
            if(killer!=null && Set.of("SHOTGUN","LASER","BAZOOKA","GRENADE").contains(p.lastWeapon)) { event("WEAPON_KILL",killer,id,p.lastWeapon,p.fell?"LAVA":"HP_ZERO"); }
            p.fell=false;p.respawnAt=p.lives>0?tick+RESPAWN_TICKS:-1;
            bodies.setParticipating(id,false);
            if(p.lives==0) { event("PLAYER_ELIMINATED",killer,id,p.lastWeapon,"NO_STOCK"); }
        }
    }
    private void event(String type,PlayerId killer,PlayerId victim,String weapon,String cause) {
        if(events.size()>=128) { throw new IllegalStateException("Bounded combat journal exhausted"); }
        events.add(new MatchEvent(events.size()+1L,tick,type,killer==null?null:killer.value(),victim==null?null:victim.value(),weapon,cause));
    }
    void finished() { frozen=snapshot();event("MATCH_END",null,null,"NONE","MATCH_END");items.close(); }
    List<MatchEvent> eventsAfter(long sequence) { return events.stream().filter(e->e.sequence()>sequence).toList(); }
    GameplaySnapshot snapshot() {
        if(frozen!=null) { return frozen; }
        var values=new ArrayList<GameplaySnapshot.Player>(4);
        for(var entry:progress.entrySet()) {
            var id=entry.getKey();var p=entry.getValue();
            String status=p.lives==0?"ELIMINATED":p.respawnAt>0?"RESPAWNING":"ACTIVE";
            values.add(new GameplaySnapshot.Player(id.value(),p.lives,status,Math.max(0,p.respawnAt-tick),Math.max(0,p.invulnerableUntil-tick),
                    Math.max(0,p.nextUltimate-tick),p.weapon,p.shield,p.damage,p.pickups,p.weapons,p.kills,p.deaths,jumps.getWallState(id),p.ammo));
        }
        return new GameplaySnapshot(setup.rules(),tick,values,items.itemViews(),items.shotViews(),fighter==null?null:fighter.snapshot());
    }
    @Override public void close() { items.close();if(fighter!=null) { fighter.close(); }events.clear();disconnected.clear();progress.clear();lastRespawn.clear();frozen=null; }
    static final class Progress {
        int lives,pickups,weapons,kills,deaths,ammo;
        long respawnAt,invulnerableUntil,nextUltimate,lastHitTick,nextShot,nextLavaDamage;
        double damage;
        boolean shield,fell;
        PlayerId lastAttacker;
        String weapon="NONE",lastWeapon="NONE";
        String koCause="BLAST_BOTTOM";
        Progress(int lives) { this.lives=lives; }
    }
}
