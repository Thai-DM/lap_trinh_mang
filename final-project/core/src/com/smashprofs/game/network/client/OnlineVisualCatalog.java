package com.smashprofs.game.network.client;

import java.util.Map;

/** Paths/scales from the original player/projectile constructors; no GL or gameplay objects. */
public final class OnlineVisualCatalog {
    public record CharacterAssets(String stand, String run) { }
    public record ProjectileAsset(String path, float scale, boolean orientFromVelocity) { }
    private static final Map<String, CharacterAssets> CHARACTERS = Map.of(
            "Alex", new CharacterAssets("Sprites/Alex/alex_stand.png", "Sprites/Alex/alex_run.png"),
            "Luca", new CharacterAssets("Sprites/Luca/luca_stand.png", "Sprites/Luca/luca_run.png"),
            "Maurice", new CharacterAssets("Sprites/Momo/momo_strip.png", "Sprites/Momo/momo_run.png"),
            "Leo", new CharacterAssets("Sprites/Leo/leo_stand.png", "Sprites/Leo/leo_run.png"),
            "Viktor", new CharacterAssets("Sprites/Viktor/viktor_stand.png", "Sprites/Viktor/viktor_run.png"));
    private static final Map<String, ProjectileAsset> PROJECTILES = Map.of(
            "IBM", new ProjectileAsset("projectiles/ibmpc.png", 0.045f, false),
            "STAR", new ProjectileAsset("projectiles/star.png", 0.5f, false),
            "MINE", new ProjectileAsset("projectiles/landminesmall.png", 1, false),
            "ROCKET", new ProjectileAsset("projectiles/missile.png", 1, true));
    private OnlineVisualCatalog() { }
    public static CharacterAssets character(String name) {
        var assets = CHARACTERS.get(name);
        if (assets == null) { throw new IllegalArgumentException("No original character assets for " + name); }
        return assets;
    }
    public static ProjectileAsset projectile(String type) {
        var assets = PROJECTILES.get(type);
        if (assets == null) { throw new IllegalArgumentException("No original projectile asset for " + type); }
        return assets;
    }
}
