package com.smashprofs.common.model;

import java.util.*;

/** Bounded immutable gameplay-v1 extension. Stats and timers originate on the simulation owner. */
public record GameplaySnapshot(MatchRules rules, long durationTicks, List<Player> players,
                               List<Item> items, List<Shot> shots) {
    public GameplaySnapshot {
        Objects.requireNonNull(rules); players=List.copyOf(players);items=List.copyOf(items);shots=List.copyOf(shots);
        if(!rules.gameplay() || durationTicks<0 || players.size()<2 || players.size()>rules.maxPlayers() || items.size()>16 || shots.size()>64) { throw new IllegalArgumentException("Invalid gameplay bounds"); }
        var ids=new HashSet<String>();
        for(var p:players) { if(!ids.add(p.playerId())) { throw new IllegalArgumentException("Duplicate progress"); } }
        var itemIds=new HashSet<Long>();for(var item:items) { if(!itemIds.add(item.id())) { throw new IllegalArgumentException("Duplicate item"); } }
        var shotIds=new HashSet<Long>();for(var shot:shots) { if(!shotIds.add(shot.id()) || !ids.contains(shot.owner())) { throw new IllegalArgumentException("Invalid projectile identity"); } }
    }
    public Player player(String id) { return players.stream().filter(p->p.playerId().equals(id)).findFirst().orElseThrow(); }
    public record Player(String playerId,int lives,String status,long respawnTicks,long invulnerableTicks,
                         long ultimateTicks,String weapon,boolean shield,double damageDealt,
                         int itemsCollected,int weaponsCollected,int kills,int deaths,String wallState,int ammo) {
        /** Older snapshots did not publish ammo. -1 means unknown, never an invented count. */
        public Player(String playerId,int lives,String status,long respawnTicks,long invulnerableTicks,long ultimateTicks,
                      String weapon,boolean shield,double damageDealt,int itemsCollected,int weaponsCollected,int kills,int deaths,String wallState) {
            this(playerId,lives,status,respawnTicks,invulnerableTicks,ultimateTicks,weapon,shield,damageDealt,itemsCollected,weaponsCollected,kills,deaths,wallState,-1);
        }
        public Player {
            if(playerId==null || playerId.isBlank() || lives<0 || lives>5 || !Set.of("ACTIVE","RESPAWNING","ELIMINATED").contains(status)
                    || respawnTicks<0 || respawnTicks>90 || invulnerableTicks<0 || invulnerableTicks>120 || ultimateTicks<0 || ultimateTicks>600
                    || !Double.isFinite(damageDealt) || damageDealt<0 || itemsCollected<0 || weaponsCollected<0 || weaponsCollected>itemsCollected || kills<0 || deaths<0
                    || !Set.of("NONE","SHOTGUN","LASER","BAZOOKA","GRENADE").contains(weapon)
                    || ammo < -1 || ammo > 6 || weapon.equals("NONE") && ammo>0
                    || !Set.of("NONE","WALL_SLIDING","WALL_JUMPING").contains(wallState)) { throw new IllegalArgumentException("Invalid player progress"); }
            if((lives==0)!=status.equals("ELIMINATED") || status.equals("RESPAWNING")!=(respawnTicks>0)) { throw new IllegalArgumentException("Inconsistent stock lifecycle"); }
        }
    }
    public record Item(long id,String type,float x,float y) {
        public Item { if(id<1 || !Set.of("SHOTGUN","LASER","BAZOOKA","GRENADE","HEALTH","SHIELD").contains(type) || !Float.isFinite(x) || !Float.isFinite(y)) { throw new IllegalArgumentException("Invalid item"); } }
    }
    public record Shot(long id,String type,String owner,float x,float y,float vx,float vy) {
        public Shot { if(id<1 || owner==null || !Set.of("SHOTGUN","LASER","BAZOOKA","GRENADE","ORBIT","EXPLOSION").contains(type)
                || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(vx) || !Float.isFinite(vy)) { throw new IllegalArgumentException("Invalid item projectile"); } }
    }
    /** Ties share the title; no slot-order tie breaker, no zero-value award. */
    public List<String> leaders(String award) {
        double best=players.stream().mapToDouble(p->score(p,award)).max().orElse(0);
        return best<=0?List.of():players.stream().filter(p->Double.compare(score(p,award),best)==0).map(Player::playerId).sorted().toList();
    }
    private static double score(Player p,String award) {
        return switch(award) { case "DAMAGE"->p.damageDealt();case "WEAPONS"->p.weaponsCollected();case "KO"->p.kills();default->throw new IllegalArgumentException("Unknown award"); };
    }
}
