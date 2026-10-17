package com.smashprofs.common.model;

import java.util.*;

/** Optional fighter-v1 state; legacy HP snapshot fields retain their original meaning. */
public record FighterSnapshot(List<Player> players) {
    public FighterSnapshot { players=List.copyOf(players);if(players.size()<2 || players.size()>4 || players.stream().map(Player::playerId).distinct().count()!=players.size()) { throw new IllegalArgumentException("Fighter roster"); } }
    public Player player(String id) { return players.stream().filter(p->p.playerId.equals(id)).findFirst().orElseThrow(); }
    public record Player(String playerId,double damagePercent,String action,String attack,long attackId,int attackFrame,
                         int hitstunTicks,int dodgeTicks,int dodgeCooldownTicks,int platformTicks,
                         boolean fastFalling,boolean recoveryAvailable,int airJumps,int wallJumps,long acknowledgedSequence,long acknowledgedClientTick) {
        public Player {
            if(playerId==null || playerId.isBlank() || !Double.isFinite(damagePercent) || damagePercent<0
                    || !Set.of("IDLE","ATTACK","CHARGE","HITSTUN","TUMBLE","DODGE","RESPAWN_PLATFORM","KO").contains(action)
                    || !Set.of("NONE","NLIGHT","SLIGHT","DLIGHT","NAIR","SAIR","DAIR","NSIG","SSIG","DSIG","RECOVERY","GROUND_POUND").contains(attack)
                    || attackId<0 || attackFrame<0 || attackFrame>180 || hitstunTicks<0 || hitstunTicks>45
                    || dodgeTicks<0 || dodgeTicks>22 || dodgeCooldownTicks<0 || dodgeCooldownTicks>150
                    || platformTicks<0 || platformTicks>180 || airJumps<0 || airJumps>1 || wallJumps<0 || wallJumps>3 || acknowledgedSequence<0 || acknowledgedClientTick<0) { throw new IllegalArgumentException("Invalid fighter state"); }
        }
    }
}
