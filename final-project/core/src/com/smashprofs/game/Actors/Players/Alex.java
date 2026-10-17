package com.smashprofs.game.Actors.Players;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.World;
import com.smashprofs.game.Helper.PlayerTypes;
/**
 * Alex as a game character.
 * Special ability:
 * Homing missile.
 */
public class Alex extends Player {

    // Character Stats (Chỉ số nhân vật: thang điểm 1 - 8)
    public static final int STAT_ATTACK    = com.smashprofs.common.model.FighterStats.forCharacter("Alex").attack(); // Sát thương
    public static final int STAT_DEFENSE   = com.smashprofs.common.model.FighterStats.forCharacter("Alex").defense(); // Phòng thủ / Giáp
    public static final int STAT_SPEED     = com.smashprofs.common.model.FighterStats.forCharacter("Alex").speed(); // Tốc độ di chuyển
    public static final int STAT_DEXTERITY = com.smashprofs.common.model.FighterStats.forCharacter("Alex").dexterity(); // Khéo léo / Hồi chiêu

    /**
     * Alex's default constructor. Creates Alex at the set spawnpoint.
     * @param world
     * The world Alex will be created in.
     * @param inputState
     * The controls Alex should listen to.
     * @param spawnpoint
     * The point in the world Alex will spawn in.
     * @param playerName
     * The name Alex will have in the game.
     * @param userData
     * Alex's userData.
     */
    public Alex(World world, InputState inputState, Vector2 spawnpoint, String playerName, String userData) {
        super(world, inputState, spawnpoint, playerName, PlayerTypes.Alex, userData, new Texture("Sprites/Alex/alex_stand.png"), new Texture("Sprites/Alex/alex_run.png"), new Texture("Sprites/Alex/alex_run.png"), new Texture("Sprites/Alex/alex_punch.png"));


    }


}
