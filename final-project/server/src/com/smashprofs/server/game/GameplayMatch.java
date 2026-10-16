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
    private final List<MatchEvent> events=new ArrayList<>(128);
    private final Set<PlayerId> disconnected=new HashSet<>();
    private final float spawnX,spawnY;
    private final Map<PlayerId,SpawnProvider.SpawnPosition> respawnPositions=new HashMap<>();
    private final List<MapCollisionDefinition.CollisionRectangle> lavaZones;
    private long tick;
    private GameplaySnapshot frozen;
    private final KnockbackScaling knockback=KnockbackScaling.configured();
    GameplayMatch(MatchSetup setup,MatchState state,PlayerPhysicsFactory.PlayerPhysicsBodies bodies,
                  PlayerJumpController jumps,MapCollisionDefinition geometry) {
        this.setup=setup;this.state=state;this.bodies=bodies;this.jumps=jumps;
        lavaZones=geometry.lavaZones();
        for(var p:setup.participants()) { progress.put(p.playerId(),new Progress(setup.rules().lives())); }
        var spawns=new SpawnProvider(geometry,0.01f);var left=spawns.getSpawn(2);var right=spawns.getSpawn(3);
        spawnX=(left.xMeters()+right.xMeters())/2;spawnY=Math.max(left.yMeters(),right.yMeters());
        for(var p:setup.participants()) { respawnPositions.put(p.playerId(),safeRespawn(geometry,p.slotIndex())); }
        items=new ItemSystem(this,geometry,new Random(setup.matchId().hashCode()));
        jumps.enableWallMovement();
    }
    synchronized void disconnect(PlayerId id) { if(progress.containsKey(id)) { disconnected.add(id); } }
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
                var position=respawnPositions.get(id);bodies.respawn(id,position.xMeters(),position.yMeters());
                jumps.resetAfterRespawn(id);event("PLAYER_RESPAWNED",null,id,"NONE","RESPAWN");
            }
            // Attribution expires by simulation time, not client timestamps.
            if(p.lastAttacker!=null && tick-p.lastHitTick>ATTRIBUTION_TICKS) { p.lastAttacker=null; }
        }
    }
    boolean active(PlayerId id) { var p=progress.get(id);return p!=null && p.lives>0 && p.respawnAt==0 && !state.getPlayer(id).isDead(); }
    private SpawnProvider.SpawnPosition safeRespawn(MapCollisionDefinition geometry,int slot) {
        float x=spawnX+(slot-1.5f)*.22f,y=spawnY,radius=PlayerPhysicsFactory.PLAYER_RADIUS_METERS+.02f;
        for(int pass=0;pass<=geometry.rectangles().size();pass++) {
            boolean raised=false;
            for(var block:geometry.rectangles()) {
                float left=block.xPixels()/100,right=(block.xPixels()+block.widthPixels())/100,bottom=block.yPixels()/100,top=(block.yPixels()+block.heightPixels())/100;
                float dx=x-Math.max(left,Math.min(x,right)),dy=y-Math.max(bottom,Math.min(y,top));
                if(dx*dx+dy*dy<radius*radius) { y=top+radius+.01f;raised=true; }
            }
            if(!raised) {
                if(x-radius<0 || x+radius>geometry.mapWidthPixels()/100 || y-radius<0 || y+radius>geometry.mapHeightPixels()/100) { throw new IllegalArgumentException("No safe central respawn"); }
                return new SpawnProvider.SpawnPosition(x,y);
            }
        }
        throw new IllegalArgumentException("Blocked central respawn");
    }
    boolean ultimateReady(PlayerId id) { return active(id) && tick>=progress.get(id).nextUltimate; }
    void ultimateUsed(PlayerId id) { progress.get(id).nextUltimate=tick+ULTIMATE_TICKS; }
    boolean damage(PlayerId attacker,PlayerId target,String weapon,float amount,float x,float y) {
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
            if(!active(id)) { continue; }var position=bodies.getPosition(id);boolean touching=position.yMeters()<0;
            for(var lava:lavaZones) {
                float left=lava.xPixels()/100,right=(lava.xPixels()+lava.widthPixels())/100,top=(lava.yPixels()+lava.heightPixels())/100;
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
            else { event("PLAYER_KILLED",killer,id,p.lastWeapon,"HP_ZERO"); }
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
        return new GameplaySnapshot(setup.rules(),tick,values,items.itemViews(),items.shotViews());
    }
    @Override public void close() { items.close();events.clear();disconnected.clear();progress.clear();respawnPositions.clear();frozen=null; }
    static final class Progress {
        int lives,pickups,weapons,kills,deaths,ammo;
        long respawnAt,invulnerableUntil,nextUltimate,lastHitTick,nextShot,nextLavaDamage;
        double damage;
        boolean shield,fell;
        PlayerId lastAttacker;
        String weapon="NONE",lastWeapon="NONE";
        Progress(int lives) { this.lives=lives; }
    }
}
