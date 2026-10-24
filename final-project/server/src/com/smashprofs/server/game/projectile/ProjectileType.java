package com.smashprofs.server.game.projectile;

/** Các ability projectile đã port, chỉ dùng nội bộ server; không thêm protocol trong common. */
public enum ProjectileType {
    IBM,
    STAR,
    MINE,
    ROCKET;

    /** Mapping authoritative từ CombatManager.shooting gốc; client không chọn type. */
    public static ProjectileType forCharacter(String characterId) {
        if (characterId == null) { return null; }
        return switch (characterId) {
            case "Viktor" -> IBM;
            case "Maurice" -> STAR;
            case "Leo" -> MINE;
            case "Alex" -> ROCKET;
            default -> null; // Circle chưa triển khai; không fallback sang skill của nhân vật khác.
        };
    }
}
