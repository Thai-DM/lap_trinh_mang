package com.smashprofs.common.model;

import java.util.*;

/** Optional fighter-v1 state; legacy HP snapshot fields retain their original meaning. */
public record FighterSnapshot(List<Player> players) {
    public FighterSnapshot { players=List.copyOf(players);if(players.size()<2 || players.size()>4 || players.stream().map(Player::playerId).distinct().count()!=players.size()) { throw new IllegalArgumentException("Fighter roster"); } }
    public Player player(String id) { return players.stream().filter(p->p.playerId.equals(id)).findFirst().orElseThrow(); }
    public record Player(String playerId,double damagePercent,String action,String attack,long attackId,int attackFrame,
                         int hitstunTicks,int dodgeTicks,int dodgeCooldownTicks,int platformTicks,
                         boolean fastFalling,boolean recoveryAvailable,int airJumps,int wallJumps,long acknowledgedSequence,long acknowledgedClientTick,Detail detail) {
        public Player(String id,double damage,String action,String attack,long attackId,int frame,int stun,int dodge,int cooldown,int platform,
                      boolean fast,boolean recovery,int jumps,int walls,long sequence,long clientTick) {
            this(id,damage,action,attack,attackId,frame,stun,dodge,cooldown,platform,fast,recovery,jumps,walls,sequence,clientTick,Detail.NONE);
        }
        public Player {
            if(playerId==null || playerId.isBlank() || !Double.isFinite(damagePercent) || damagePercent<0
                    || !Set.of("IDLE","ATTACK","CHARGE","HITSTUN","TUMBLE","DODGE","RESPAWN_PLATFORM","KO").contains(action)
                    || !Set.of("NONE","NLIGHT","NLIGHT2","NLIGHT3","SLIGHT","DLIGHT","NAIR","SAIR","DAIR","NHEAVY","SHEAVY","DHEAVY","NSIG","SSIG","DSIG","RECOVERY","GROUND_POUND").contains(attack)
                    || attackId<0 || attackFrame<0 || attackFrame>180 || hitstunTicks<0 || hitstunTicks>45
                    || dodgeTicks<0 || dodgeTicks>30 || dodgeCooldownTicks<0 || dodgeCooldownTicks>UnarmedRules.AIR_COOLDOWN
                    || platformTicks<0 || platformTicks>180 || airJumps<0 || airJumps>UnarmedRules.AIR_JUMPS || wallJumps<0 || wallJumps>3 || acknowledgedSequence<0 || acknowledgedClientTick<0 || detail==null) { throw new IllegalArgumentException("Invalid fighter state"); }
        }
    }
    public record Detail(String state,String phase,String dodgeKind,int recoveries,int aerialActionsUsed,int gravityCancelTicks,
                         int chaseCharges,int chaseTicks,int bufferTicks,boolean gravityCancelled,int wallActions,
                         int dodgeFrames,String combo,long hitSerial,int dashTicks,float dashVelocity,int wallLockTicks,int runTicks,int dashCooldownTicks,int attackDirection) {
        public static final Detail NONE=new Detail("IDLE","NONE","NONE",0,0,0,0,0,0,false,0,-1,"UNVERIFIED",0,0,0,0,0,0,1);
        public Detail {
            if(!Set.of("IDLE","RUN","DASH","BACKDASH","SPRINT","AIR_RISING","AIR_FALLING","FAST_FALL","WALL_SLIDE","WALL_SLIP","ATTACK","CHARGE","GRAVITY_CANCEL","DODGE","CHASE_DODGE","HITSTUN","TUMBLE","KO","RESPAWN_PLATFORM").contains(state)
                    || !Set.of("NONE","STARTUP","ACTIVE","RECOVERY","CHARGE").contains(phase)
                    || !Set.of("NONE","GROUND_SPOT","AIR_SPOT","AIR_DIRECTION","CHASE").contains(dodgeKind)
                    || recoveries<0 || recoveries>3 || aerialActionsUsed<0 || aerialActionsUsed>3
                    || gravityCancelTicks<0 || gravityCancelTicks>18 || chaseCharges<0 || chaseCharges>2 || chaseTicks<0 || chaseTicks>30
                    || bufferTicks<0 || bufferTicks>12 || wallActions<0 || wallActions>99 || dodgeFrames< -1 || dodgeFrames>999
                    || !Set.of("UNVERIFIED","ZERO_WINDOW","STRING","DODGE_UNAVAILABLE").contains(combo) || hitSerial<0
                    || dashTicks<0 || dashTicks>12 || !Float.isFinite(dashVelocity) || Math.abs(dashVelocity)>10 || wallLockTicks<0 || wallLockTicks>12
                    || runTicks<0 || runTicks>18 || dashCooldownTicks<0 || dashCooldownTicks>24 || Math.abs(attackDirection)!=1) { throw new IllegalArgumentException("Invalid unarmed detail"); }
        }
    }
}
