package com.smashprofs.game.Helper;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;
import java.util.EnumMap;
import java.util.Map;

/**
 * Global customizable keybinding configuration.
 * Persists user customizations across sessions using LibGDX Preferences.
 */
public final class KeyBindingConfig {
    public enum Action {
        LEFT("Sang trái", Input.Keys.A),
        RIGHT("Sang phải", Input.Keys.D),
        UP("Hướng lên / Ngắm", Input.Keys.I),
        DOWN("Hướng xuống / Rơi", Input.Keys.S),
        JUMP("Nhảy", Input.Keys.W),
        LIGHT("Đánh nhẹ / Bắn", Input.Keys.J),
        HEAVY("Đánh mạnh", Input.Keys.K),
        DODGE("Né / Lướt", Input.Keys.L),
        ABILITY("Kỹ năng F", Input.Keys.F),
        INTERACT("Nhặt đồ", Input.Keys.E),
        DROP("Thả đồ", Input.Keys.G),
        THROW("Ném vũ khí", Input.Keys.Q);

        public final String label;
        public final int defaultKey;

        Action(String label, int defaultKey) {
            this.label = label;
            this.defaultKey = defaultKey;
        }
    }

    public enum Preset {
        WASD, ARROWS
    }

    private static final String PREF_NAME = "smash-profs-keybindings";
    private static final String VERSION_KEY = "bindingsVersion";
    private static final int VERSION = 1;
    private static KeyBindingConfig instance;

    private final Map<Action, Integer> bindings = new EnumMap<>(Action.class);

    private KeyBindingConfig() {
        load();
    }

    public static synchronized KeyBindingConfig getInstance() {
        if (instance == null) {
            instance = new KeyBindingConfig();
        }
        return instance;
    }

    public synchronized void load() {
        Preferences prefs = Gdx.app != null ? Gdx.app.getPreferences(PREF_NAME) : null;
        for (Action action : Action.values()) {
            int key = prefs != null ? prefs.getInteger(action.name(), action.defaultKey) : action.defaultKey;
            bindings.put(action, key);
        }
        // Upgrade only the old default WASD pair. Explicit new bindings and other layouts are preserved.
        if (prefs != null && prefs.getInteger(VERSION_KEY, 0) < VERSION) {
            if (getKey(Action.LEFT) == Input.Keys.A && getKey(Action.RIGHT) == Input.Keys.D
                    && getKey(Action.DOWN) == Input.Keys.S && getKey(Action.UP) == Input.Keys.W
                    && getKey(Action.JUMP) == Input.Keys.SPACE) {
                bindings.put(Action.JUMP, Input.Keys.W);
                bindings.put(Action.UP, Input.Keys.I);
                prefs.putInteger(Action.JUMP.name(), Input.Keys.W);
                prefs.putInteger(Action.UP.name(), Input.Keys.I);
            }
            prefs.putInteger(VERSION_KEY, VERSION);
            prefs.flush();
        }
    }

    public synchronized void save() {
        if (Gdx.app == null) return;
        Preferences prefs = Gdx.app.getPreferences(PREF_NAME);
        for (Map.Entry<Action, Integer> entry : bindings.entrySet()) {
            prefs.putInteger(entry.getKey().name(), entry.getValue());
        }
        prefs.putInteger(VERSION_KEY, VERSION);
        prefs.flush();
    }

    public synchronized void applyPreset(Preset preset) {
        if (preset == Preset.ARROWS) {
            bindings.put(Action.LEFT, Input.Keys.LEFT);
            bindings.put(Action.RIGHT, Input.Keys.RIGHT);
            bindings.put(Action.UP, Input.Keys.UP);
            bindings.put(Action.DOWN, Input.Keys.DOWN);
            bindings.put(Action.JUMP, Input.Keys.SPACE);
            bindings.put(Action.LIGHT, Input.Keys.C);
            bindings.put(Action.HEAVY, Input.Keys.X);
            bindings.put(Action.DODGE, Input.Keys.Z);
            bindings.put(Action.ABILITY, Input.Keys.P);
            bindings.put(Action.INTERACT, Input.Keys.E);
            bindings.put(Action.DROP, Input.Keys.G);
            bindings.put(Action.THROW, Input.Keys.Q);
        } else {
            for (Action action : Action.values()) {
                bindings.put(action, action.defaultKey);
            }
        }
        save();
    }

    public synchronized void resetDefaults() {
        applyPreset(Preset.WASD);
    }

    public synchronized int getKey(Action action) {
        return bindings.getOrDefault(action, action.defaultKey);
    }

    public synchronized void setKey(Action action, int keycode) {
        bindings.put(action, keycode);
    }

    public static String keyName(int keycode) {
        return switch (keycode) {
            case Input.Keys.SPACE -> "Space";
            case Input.Keys.UP -> "Up";
            case Input.Keys.DOWN -> "Down";
            case Input.Keys.LEFT -> "Left";
            case Input.Keys.RIGHT -> "Right";
            case Input.Keys.CONTROL_LEFT -> "L-Ctrl";
            case Input.Keys.CONTROL_RIGHT -> "R-Ctrl";
            case Input.Keys.SHIFT_LEFT -> "L-Shift";
            case Input.Keys.SHIFT_RIGHT -> "R-Shift";
            case Input.Keys.ALT_LEFT -> "L-Alt";
            case Input.Keys.ALT_RIGHT -> "R-Alt";
            case Input.Keys.ENTER -> "Enter";
            case Input.Keys.TAB -> "Tab";
            case Input.Keys.ESCAPE -> "Esc";
            case Input.Keys.BACKSPACE -> "Backsp";
            default -> {
                String str = Input.Keys.toString(keycode);
                yield str != null && !str.isBlank() ? str.toUpperCase() : "KEY_" + keycode;
            }
        };
    }
}
