package com.smashprofs.common.model;

import java.util.Set;

/** Immutable negotiated gameplay-v1 rules. Zero seconds means last-player-standing. */
public record MatchRules(int maxPlayers, int matchSeconds, int lives, DropRate dropRate,
                         boolean gameplay, boolean fighter, boolean keepWeaponOnThrow, boolean boss) {
    public MatchRules(int maxPlayers,int matchSeconds,int lives,DropRate dropRate,boolean gameplay) {
        this(maxPlayers,matchSeconds,lives,dropRate,gameplay,false,false,false);
    }
    public MatchRules(int maxPlayers,int matchSeconds,int lives,DropRate dropRate,boolean gameplay,boolean fighter) {
        this(maxPlayers,matchSeconds,lives,dropRate,gameplay,fighter,false,false);
    }
    public MatchRules(int maxPlayers,int matchSeconds,int lives,DropRate dropRate,boolean gameplay,boolean fighter,boolean keepWeaponOnThrow) {
        this(maxPlayers,matchSeconds,lives,dropRate,gameplay,fighter,keepWeaponOnThrow,false);
    }
    public enum DropRate {
        NONE(0), LOW(1800), MEDIUM(900), HIGH(480), VERY_HIGH(240);
        public final int intervalTicks;
        DropRate(int ticks) { intervalTicks=ticks; }
    }
    public static final MatchRules LEGACY = new MatchRules(4,120,1,DropRate.NONE,false);
    public static final MatchRules DEFAULT = new MatchRules(4,120,3,DropRate.MEDIUM,true);
    public static final MatchRules FIGHTER = new MatchRules(4,120,3,DropRate.MEDIUM,true,true);
    public static final MatchRules BOSS = new MatchRules(4,180,3,DropRate.HIGH,true,true,false,true);
    public MatchRules {
        if(maxPlayers<2 || maxPlayers>4 || !Set.of(0,60,90,120,180,300,360).contains(matchSeconds)
                || !Set.of(1,2,3,5).contains(lives) || dropRate==null || fighter && !gameplay || boss && (!fighter || matchSeconds==0)) { throw new IllegalArgumentException("Invalid match rules"); }
    }
}
