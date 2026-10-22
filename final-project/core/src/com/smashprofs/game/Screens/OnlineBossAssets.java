package com.smashprofs.game.Screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.smashprofs.common.model.BossSnapshot;
import java.util.*;

/** Boss art is loaded during LOAD_READY and disposed with the arena. Animation never applies damage. */
final class OnlineBossAssets implements AutoCloseable {
    private final List<Texture> textures=new ArrayList<>();
    private final Map<String,TextureRegion[]> frames=new HashMap<>(),flipped=new HashMap<>();
    private final Texture fire,explosion;
    private long lastTick=-1,lastHit;
    private float elapsed,hitFlash;
    OnlineBossAssets() {
        try {
        for(var a:List.of(new String[]{"idle","01_demon_idle","6"},new String[]{"walk","02_demon_walk","12"},
                new String[]{"cleave","03_demon_cleave","15"},new String[]{"take_hit","04_demon_take_hit","5"},new String[]{"death","05_demon_death","22"})) {
            int count=Integer.parseInt(a[2]);var right=new TextureRegion[count];var left=new TextureRegion[count];
            for(int i=0;i<count;i++) {
                var t=load("boss/demon-slime/individual sprites/"+a[1]+"/demon_"+a[0]+"_"+(i+1)+".png");
                right[i]=new TextureRegion(t);left[i]=new TextureRegion(t);left[i].flip(true,false);
            }
            frames.put(a[0],right);flipped.put(a[0],left);
        }
        fire=load("boss/effects/kenney-particle-pack/PNG (Transparent)/fire_01.png");
        explosion=load("boss/effects/more-explosions/test.png");
        } catch(RuntimeException|Error error) { textures.forEach(Texture::dispose);throw error; }
    }
    private Texture load(String path) {
        var t=new Texture(Gdx.files.internal(path));t.setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest);textures.add(t);return t;
    }
    void update(BossSnapshot b,long tick,float delta) {
        if(tick!=lastTick) { lastTick=tick;elapsed=0; }else { elapsed+=Math.max(0,Math.min(delta,.1f)); }
        hitFlash=Math.max(0,hitFlash-delta);
        if(b.hitSerial()!=lastHit) { lastHit=b.hitSerial();hitFlash=.10f; }
    }
    private float age(BossSnapshot b,long tick) { return Math.max(0,tick-b.actionTick())/60f+(b.action().equals("DEAD")?elapsed:Math.min(.05f,elapsed)); }
    void warnings(ShapeRenderer shapes,BossSnapshot b) {
        Gdx.gl.glEnable(GL20.GL_BLEND);Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for(var h:b.hazards()) {
            if(h.type().equals("ERUPTION")) {
                shapes.setColor(h.active()?1:.95f,h.active()?.45f:.12f,.06f,h.active()?.45f:.28f);
                shapes.ellipse(h.x()-h.radius(),h.y()-.07f,h.radius()*2,.14f,40);
                shapes.rect(h.x()-h.radius(),h.y()-.02f,h.radius()*2,.02f);
            }
        }
        if(b.attack().equals("CLEAVE") && (b.action().equals("TELEGRAPH")||b.action().equals("ACTIVE"))) {
            shapes.setColor(1,.18f,.04f,b.action().equals("ACTIVE")?.45f:.20f);
            shapes.rect(b.x()+(b.facingRight()?-.1f:-1.05f),b.y(),1.15f,.7f);
        }
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for(var h:b.hazards()) { if(h.type().equals("ERUPTION")&&!h.active()) { shapes.setColor(Color.SCARLET);shapes.ellipse(h.x()-h.radius(),h.y()-.07f,h.radius()*2,.14f,32); } }
        shapes.end();
    }
    void draw(SpriteBatch batch,BossSnapshot b,long tick) {
        batch.setColor(Color.WHITE);
        float age=age(b,tick);String anim;int index;
        switch(b.action()) {
            case "DEAD" -> { anim="death";index=Math.min(21,(int)(age*10)); }
            case "TELEGRAPH" -> { anim="cleave";index=Math.min(3,(int)(age*5)); }
            case "ACTIVE" -> { anim="cleave";index=4+Math.min(6,(int)(age*35)); }
            case "RECOVERY" -> { anim="cleave";index=11+Math.min(3,(int)(age*10)); }
            case "WALK" -> { anim="walk";index=(int)(tick/6)%12; }
            default -> { anim=hitFlash>0?"take_hit":"idle";index=hitFlash>0?0:(int)(tick/6)%6; }
        }
        var region=(b.facingRight()?frames:flipped).get(anim)[index];
        if(hitFlash>0) { batch.setColor(1,.65f,.55f,1); }
        else if(b.phase()==2) { batch.setColor(1,.8f,.75f,1); }
        batch.draw(region,b.x()-144*.007f,b.y()-.007f,288*.007f,160*.007f);batch.setColor(Color.WHITE);
        for(var h:b.hazards()) {
            if(h.type().equals("FIREBALL")) {
                float extra=Math.min(.05f,elapsed),x=h.x()+h.vx()*extra,y=h.y()+h.vy()*extra;
                batch.setColor(1,.45f,.10f,1);batch.draw(fire,x-.16f,y-.16f,.32f,.32f);batch.setColor(Color.WHITE);
            } else if(h.active()) {
                int f=Math.min(59,(int)((15-h.remainingTicks())*4));
                batch.draw(new TextureRegion(explosion,f%10*100,f/10*100,100,100),h.x()-.6f,h.y()-.20f,1.2f,1.2f);
            }
        }
        if(b.action().equals("TELEGRAPH") && b.attack().equals("FIREBALL")) {
            batch.setColor(1,.45f,.1f,.8f);batch.draw(fire,b.x()-.13f,b.y()+.22f,.26f,.26f);batch.setColor(Color.WHITE);
        }
    }
    @Override public void close() { textures.forEach(Texture::dispose);textures.clear(); }
}
