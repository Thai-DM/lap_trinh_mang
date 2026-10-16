package com.smashprofs.server.game;

/** Configurable bounded impulse multiplier. Inputs in meters/Box2D impulse units. */
public record KnockbackScaling(float maxMultiplier,float maxImpulse) {
    public static final KnockbackScaling DEFAULT=new KnockbackScaling(4,8);
    public static KnockbackScaling configured() {
        return new KnockbackScaling(Float.parseFloat(System.getProperty("smashprofs.gameplay.maxKnockbackMultiplier","4")),
                Float.parseFloat(System.getProperty("smashprofs.gameplay.maxKnockbackImpulse","8")));
    }
    public KnockbackScaling { if(!Float.isFinite(maxMultiplier) || !Float.isFinite(maxImpulse) || maxMultiplier<1 || maxImpulse<=0) { throw new IllegalArgumentException("Invalid knockback bounds"); } }
    public float multiplier(float hp) {
        if(!Float.isFinite(hp)) { throw new IllegalArgumentException("Finite HP required"); }
        float lost=1-Math.max(0,Math.min(100,hp))/100;
        return 1+(maxMultiplier-1)*lost*lost;
    }
    public Impulse scale(float hp,float x,float y) {
        if(!Float.isFinite(x) || !Float.isFinite(y)) { throw new IllegalArgumentException("Finite impulse required"); }
        double factor=multiplier(hp),sx=x*factor,sy=y*factor,length=Math.hypot(sx,sy);
        if(length>maxImpulse) { sx*=maxImpulse/length;sy*=maxImpulse/length; }
        return new Impulse((float)sx,(float)sy);
    }
    public record Impulse(float x,float y) { }
}
