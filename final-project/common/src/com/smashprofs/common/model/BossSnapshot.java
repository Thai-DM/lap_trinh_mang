package com.smashprofs.common.model;

import java.util.*;

/** One bounded authoritative PvE entity, independent of the human connection roster. Units: metres/ticks. */
public record BossSnapshot(float x,float y,boolean facingRight,float hp,float maxHp,int phase,
        String action,String attack,long actionTick,long attackId,long hitSerial,List<Hazard> hazards) {
    public static final String CAPABILITY="boss-v1";
    public static final int MAX_HAZARDS=24;
    public BossSnapshot {
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(hp)||!Float.isFinite(maxHp)
                ||hp<0||maxHp<=0||maxHp>10000||hp>maxHp||phase<1||phase>2||actionTick<0||attackId<0||hitSerial<0
                ||!Set.of("IDLE","WALK","TELEGRAPH","ACTIVE","RECOVERY","DEAD").contains(action)
                ||!Set.of("NONE","CLEAVE","FIREBALL","ERUPTION").contains(attack)
                ||hp==0!=action.equals("DEAD")) { throw new IllegalArgumentException("Invalid boss state"); }
        hazards=List.copyOf(hazards);
        if(hazards.size()>MAX_HAZARDS || hazards.stream().map(Hazard::id).distinct().count()!=hazards.size()) { throw new IllegalArgumentException("Invalid boss hazards"); }
    }
    public record Hazard(long id,String type,float x,float y,float vx,float vy,float radius,long remainingTicks,boolean active) {
        public Hazard {
            if(id<1||!Set.of("FIREBALL","ERUPTION").contains(type)||!Float.isFinite(x)||!Float.isFinite(y)
                    ||!Float.isFinite(vx)||!Float.isFinite(vy)||!Float.isFinite(radius)||radius<=0||radius>2
                    ||remainingTicks<0||remainingTicks>600) { throw new IllegalArgumentException("Invalid boss hazard"); }
        }
    }
}
