package com.smashprofs.game.Helper;

import com.badlogic.gdx.Gdx;

/** Device preferences; gains are multiplied, so master zero mutes both channels. */
public final class AudioSettings {
    private static AudioSettings instance;
    private float master=1,music=.6f,effects=.8f;
    public AudioSettings() { load(); }
    public static synchronized AudioSettings get() { if(instance==null) { instance=new AudioSettings(); }return instance; }
    public void load() {
        if(Gdx.app!=null) {
            var p=Gdx.app.getPreferences("smashprofs-audio");
            master=clamp(p.getFloat("master",1));music=clamp(p.getFloat("music",.6f));effects=clamp(p.getFloat("effects",.8f));
        }
    }
    private static float clamp(float value) { return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0; }
    public void set(float master,float music,float effects) {
        this.master=clamp(master);this.music=clamp(music);this.effects=clamp(effects);
        if(Gdx.app!=null) { Gdx.app.getPreferences("smashprofs-audio").putFloat("master",this.master).putFloat("music",this.music).putFloat("effects",this.effects).flush(); }
    }
    public float master() { return master; }
    public float music() { return music; }
    public float effects() { return effects; }
    public float musicGain() { return master*music; }
    public float effectGain(float gain) { return clamp(gain)*master*effects; }
}
