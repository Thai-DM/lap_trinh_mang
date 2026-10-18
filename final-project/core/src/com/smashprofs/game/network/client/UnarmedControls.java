package com.smashprofs.game.network.client;

import com.badlogic.gdx.Input;
import com.smashprofs.common.model.FighterInput;
import java.util.function.IntPredicate;

/** Both presets produce the same logical input. Jump and upward aim are separate bindings. */
public final class UnarmedControls {
    public enum Preset { B, A }
    public record Frame(float horizontal,boolean jumpPressed,boolean lightPressed,boolean interactPressed,boolean dropPressed,FighterInput fighter) { }
    private UnarmedControls() { }
    public static Frame sample(Preset preset,IntPredicate held,IntPredicate pressed) {
        int left=preset==Preset.B?Input.Keys.A:Input.Keys.LEFT,right=preset==Preset.B?Input.Keys.D:Input.Keys.RIGHT;
        int jump=preset==Preset.B?Input.Keys.W:Input.Keys.UP,down=preset==Preset.B?Input.Keys.S:Input.Keys.DOWN;
        int light=preset==Preset.B?Input.Keys.J:Input.Keys.C,heavy=preset==Preset.B?Input.Keys.K:Input.Keys.X,dodge=preset==Preset.B?Input.Keys.L:Input.Keys.Z;
        return new Frame((held.test(right)?1:0)-(held.test(left)?1:0),pressed.test(jump),pressed.test(light),pressed.test(Input.Keys.E),pressed.test(Input.Keys.G),
                new FighterInput(held.test(down),held.test(jump),held.test(Input.Keys.I),pressed.test(heavy),held.test(heavy),pressed.test(dodge),pressed.test(Input.Keys.Q),false));
    }
    public static String hint(Preset preset) { return preset==Preset.B?"A/D · W nhảy · S xuống · J nhẹ · K mạnh · L né/lướt":"←/→ · ↑ nhảy · ↓ xuống · C nhẹ · X mạnh · Z né/lướt"; }
}
