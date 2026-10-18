package com.smashprofs.common.model;

/** One shared resource policy for native authority and render-owned movement replay. */
public final class AirActionBudget {
    private int jumps=UnarmedRules.AIR_JUMPS,recoveries,used;
    public AirActionBudget() { }
    public AirActionBudget(int jumps,int recoveries,int used) { this.jumps=jumps;this.recoveries=recoveries;this.used=used; }
    public void reset() { jumps=UnarmedRules.AIR_JUMPS;recoveries=used=0; }
    public boolean jump() { if(jumps==0 || used>=UnarmedRules.AIR_ACTIONS) { return false; }jumps--;used++;return true; }
    public boolean canRecover() { return used<UnarmedRules.AIR_ACTIONS && (recoveries==0 || jumps>0); }
    public float recover() {
        if(!canRecover()) { return 0; }
        if(recoveries>0) { jumps--; }used++;
        return recoveries++==0?4.8f:UnarmedRules.EXHAUSTED_RECOVERY_SPEED;
    }
    public int jumps() { return jumps; }public int recoveries() { return recoveries; }public int used() { return used; }
}
