package com.smashprofs.common.model;

/** Internal Unarmed prototype balance. These are tunable values, not Brawlhalla frame data. */
public final class FighterMoves {
    private FighterMoves() { }
    public record Move(String name,int startup,int active,int recovery,float damage,float base,float scaling,
                       float angle,float x,float y,float halfWidth,float halfHeight,boolean heavy,
                       int missRecovery,int baseStun,int minCharge,int maxCharge) {
        public int total() { return startup+active+recovery; }
        public int total(boolean hit) { return startup+active+(hit?recovery:missRecovery); }
        public boolean activeAt(int frame) { return frame>=startup && frame<startup+active; }
        public int[] activeFrames() { return java.util.stream.IntStream.range(startup,startup+active).toArray(); }
        public boolean chargeable() { return maxCharge>0; }
        public String group() { return (name.endsWith("LIGHT") || name.startsWith("NLIGHT"))?"unarmed_ground_light":name.endsWith("HEAVY")?"unarmed_ground_heavy":heavy?"unarmed_air_heavy":"unarmed_air_light"; }
        public String phase(int frame) { return frame<startup?"STARTUP":activeAt(frame)?"ACTIVE":"RECOVERY"; }
        public String requiredState() { return (name.endsWith("LIGHT") || name.startsWith("NLIGHT") || name.endsWith("HEAVY"))?"Grounded":"Airborne"; }
        public int fixedRecoveryOnHit() { return recovery; }public int variableRecoveryOnHit() { return 0; }
        public int fixedRecoveryOnMiss() { return missRecovery; }public int variableRecoveryOnMiss() { return 0; }
    }
    public static Move get(String name) {
        return switch(name) {
            case "NLIGHT", "NLIGHT1"->new Move("NLIGHT",3,4,10,7,.27f,.07f,25,.135f,.10f,.055f,.14f,false,14,12,0,0);
            case "NLIGHT2"->new Move("NLIGHT2",3,4,10,7,.27f,.07f,25,.14f,.10f,.055f,.14f,false,14,12,0,0);
            case "NLIGHT3"->new Move("NLIGHT3",4,5,12,8,.28f,.07f,30,.145f,.10f,.06f,.15f,false,16,14,0,0);
            case "SLIGHT"->new Move(name,4,4,12,8,.30f,.07f,20,.18f,.10f,.08f,.14f,false,17,14,0,0);
            case "DLIGHT"->new Move(name,4,5,9,7,.7f,.22f,70,.14f,-.01f,.08f,.08f,false,18,18,0,0);
            case "NAIR"->new Move(name,3,6,12,7,.8f,.20f,70,0,.10f,.14f,.18f,false,17,14,0,0);
            case "SAIR"->new Move(name,4,5,14,9,1,.24f,30,.15f,.10f,.07f,.14f,false,19,16,0,0);
            case "DAIR"->new Move(name,5,5,16,9,.9f,.24f,-80,.05f,-.16f,.10f,.16f,false,22,17,0,0);
            case "NHEAVY"->new Move(name,10,5,23,16,1.6f,.30f,75,.06f,.24f,.12f,.20f,true,30,24,4,30);
            case "SHEAVY"->new Move(name,12,5,25,18,1.7f,.32f,25,.19f,.10f,.09f,.16f,true,33,26,6,40);
            case "DHEAVY"->new Move(name,12,7,25,16,1.5f,.30f,40,0,.01f,.22f,.12f,true,34,25,5,36);
            case "RECOVERY"->new Move(name,8,7,20,12,1.3f,.25f,80,0,.26f,.18f,.30f,true,27,21,0,0);
            case "GROUND_POUND"->new Move(name,8,10,24,16,1.6f,.30f,-85,0,-.18f,.20f,.24f,true,32,25,0,0);
            case "NSIG"->get("NHEAVY");case "SSIG"->get("SHEAVY");case "DSIG"->get("DHEAVY");
            default->throw new IllegalArgumentException("Unknown move: "+name);
        };
    }
    public static double knockback(double damage,float base,float scaling,int defense) {
        if(!Double.isFinite(damage) || damage<0 || !Float.isFinite(base) || base<0 || !Float.isFinite(scaling) || scaling<0 || defense<0) { throw new IllegalArgumentException("Knockback"); }
        return Math.min(50,(base+damage*scaling/10)*200/(defense+100));
    }
    public static int hitstun(double damage,float scaling) { return (int)Math.ceil(Math.min(.75,.15+damage*scaling/600)*60); }
    public static float hurtboxHigh(String move,int frame) {
        return (move.equals("DLIGHT") || move.equals("DHEAVY")) && get(move).activeAt(frame)?.07f:.17f;
    }
}
