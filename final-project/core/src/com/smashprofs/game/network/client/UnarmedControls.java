package com.smashprofs.game.network.client;

import com.badlogic.gdx.Input;
import com.smashprofs.common.model.FighterInput;
import java.util.function.IntPredicate;

/** Both presets produce the same logical input. Jump and upward aim are separate bindings. */
public final class UnarmedControls {
    public enum Preset { B, A }
    public record Frame(float horizontal,boolean jumpPressed,boolean lightPressed,boolean lightHeld,boolean interactPressed,boolean dropPressed,FighterInput fighter) {
        public Frame(float horizontal,boolean jumpPressed,boolean lightPressed,boolean interactPressed,boolean dropPressed,FighterInput fighter) {
            this(horizontal,jumpPressed,lightPressed,false,interactPressed,dropPressed,fighter);
        }
    }
    private static long lastDownPressTime;
    private UnarmedControls() { }
    public static Frame sample(Preset preset,IntPredicate held,IntPredicate pressed) {
        var cfg=com.smashprofs.game.Helper.KeyBindingConfig.getInstance();
        int left=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LEFT);
        int right=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.RIGHT);
        int up=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.UP);
        int down=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DOWN);
        int jump=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.JUMP);
        int light=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LIGHT);
        int heavy=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.HEAVY);
        int dodge=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DODGE);
        int interact=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.INTERACT);
        int drop=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DROP);
        int throwKey=cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.THROW);
        boolean dodgePressed=pressed.test(dodge);
        boolean leftHeld=held.test(left),rightHeld=held.test(right);
        boolean leftPressed=pressed.test(left),rightPressed=pressed.test(right);
        float h=(rightHeld || rightPressed?1:0)-(leftHeld || leftPressed?1:0);
        // One physical press selects one action. Changing direction while holding dodge must not retrigger it.
        boolean dashIntent=dodgePressed && h!=0;
        boolean dodgeEdge=dodgePressed && !dashIntent;
        boolean downPressed=pressed.test(down);
        long now=System.currentTimeMillis();
        boolean doubleTapDown=downPressed && (now-lastDownPressTime<=350);
        if(downPressed) { lastDownPressTime=now; }
        boolean attacking=pressed.test(light) || pressed.test(heavy);
        boolean dropPressed=pressed.test(drop) || doubleTapDown && !attacking;
        boolean jumpPressed=pressed.test(jump);
        boolean jumpHeld=held.test(jump);
        boolean upHeld=held.test(up) || pressed.test(up);
        boolean downHeld=held.test(down) || pressed.test(down);
        return new Frame(h,jumpPressed,pressed.test(light),held.test(light),pressed.test(interact),dropPressed,
                new FighterInput(downHeld,jumpHeld,upHeld,pressed.test(heavy),held.test(heavy),dodgeEdge,pressed.test(throwKey),dashIntent));
    }
    public static String hint() {
        var cfg=com.smashprofs.game.Helper.KeyBindingConfig.getInstance();
        String u=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.UP));
        String l=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LEFT));
        String d=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DOWN));
        String r=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.RIGHT));
        String jm=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.JUMP));
        String lt=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.LIGHT));
        String hv=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.HEAVY));
        String dg=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.DODGE));
        String ab=com.smashprofs.game.Helper.KeyBindingConfig.keyName(cfg.getKey(com.smashprofs.game.Helper.KeyBindingConfig.Action.ABILITY));
        return u+"/"+l+"/"+d+"/"+r+" ngắm • "+jm+" nhảy • "+d+" 2 lần xuống sàn • "+d+"+"+lt+" đánh xuống / bắn xuống khi cầm súng • "+hv+" mạnh • "+dg+" né • "+ab+" kỹ năng";
    }
    public static String hint(Preset preset) { return hint(); }
}
