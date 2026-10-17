package com.smashprofs.common.model;

/** Tick-based move catalogue. Rectangles are relative to body center, facing right. */
public final class FighterMoves {
    private FighterMoves() { }
    public record Move(String name,int startup,int active,int recovery,float damage,float base,float scaling,
                       float angle,float x,float y,float halfWidth,float halfHeight,boolean heavy) {
        public int total() { return startup+active+recovery; }
        public boolean activeAt(int frame) { return frame>=startup && frame<startup+active; }
    }
    public static Move get(String name) {
        return switch(name) {
            case "NLIGHT"->new Move(name,3,4,10,7,.8f,.20f,25,.17f,.10f,.16f,.14f,false);
            case "SLIGHT"->new Move(name,4,4,12,8,.9f,.22f,20,.22f,.08f,.20f,.12f,false);
            case "DLIGHT"->new Move(name,4,5,13,7,.7f,.22f,70,.15f,-.01f,.22f,.08f,false);
            case "NAIR"->new Move(name,3,6,12,7,.8f,.20f,70,0,.10f,.23f,.25f,false);
            case "SAIR"->new Move(name,4,5,14,9,1,.24f,30,.23f,.10f,.23f,.15f,false);
            case "DAIR"->new Move(name,5,5,16,9,.9f,.24f,-80,.05f,-.16f,.16f,.22f,false);
            case "NSIG"->new Move(name,10,5,23,16,1.6f,.30f,75,.06f,.27f,.22f,.29f,true);
            case "SSIG"->new Move(name,12,5,25,18,1.7f,.32f,25,.30f,.10f,.29f,.18f,true);
            case "DSIG"->new Move(name,12,7,25,16,1.5f,.30f,40,0,.01f,.40f,.12f,true);
            case "RECOVERY"->new Move(name,8,7,20,12,1.3f,.25f,80,0,.26f,.18f,.30f,true);
            case "GROUND_POUND"->new Move(name,8,10,24,16,1.6f,.30f,-85,0,-.18f,.20f,.24f,true);
            default->throw new IllegalArgumentException("Unknown move: "+name);
        };
    }
    public static double knockback(double damage,float base,float scaling,int defense) {
        if(!Double.isFinite(damage) || damage<0 || !Float.isFinite(base) || base<0 || !Float.isFinite(scaling) || scaling<0 || defense<0) { throw new IllegalArgumentException("Knockback"); }
        return Math.min(50,(base+damage*scaling/10)*200/(defense+100));
    }
    public static int hitstun(double damage,float scaling) { return (int)Math.ceil(Math.min(.75,.15+damage*scaling/600)*60); }
}
