package com.smashprofs.game.Actors.Players;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.World;
import com.smashprofs.game.Helper.PlayerTypes;
/**
 * Luca as a game character.
 * Special ability:
 * Up to 2 orbiting defense shurikens.
 */
public class Luca extends Player {

    // Character Stats (Chỉ số nhân vật: thang điểm 1 - 8)
    public static final int STAT_ATTACK    = com.smashprofs.common.model.FighterStats.forCharacter("Luca").attack(); // Sát thương
    public static final int STAT_DEFENSE   = com.smashprofs.common.model.FighterStats.forCharacter("Luca").defense(); // Phòng thủ / Giáp
    public static final int STAT_SPEED     = com.smashprofs.common.model.FighterStats.forCharacter("Luca").speed(); // Tốc độ di chuyển
    public static final int STAT_DEXTERITY = com.smashprofs.common.model.FighterStats.forCharacter("Luca").dexterity(); // Khéo léo / Hồi chiêu

    /**
     * Luca's default constructor. Creates Luca at the set spawnpoint.
     * @param world
     * The world Luca will be created in.
     * @param inputState
     * The controls Luca should listen to.
     * @param spawnpoint
     * The point in the world Luca will spawn in.
     * @param playerName
     * The name Luca will have in the game.
     * @param userData
     * Luca's userData.
     */
    public Luca(World world, InputState inputState, Vector2 spawnpoint, String playerName, String userData) {
        super(world, inputState, spawnpoint, playerName, PlayerTypes.Luca, userData, new Texture("Sprites/Luca/luca_stand.png"), new Texture("Sprites/Luca/luca_run.png"), new Texture("Sprites/Luca/luca_run.png"), new Texture("Sprites/Luca/luca_punch.png"));


    }
}
