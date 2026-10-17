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
    public static final int STAT_ATTACK    = 7; // Sát thương
    public static final int STAT_DEFENSE   = 6; // Phòng thủ / Giáp
    public static final int STAT_SPEED     = 5; // Tốc độ di chuyển
    public static final int STAT_DEXTERITY = 5; // Khéo léo / Hồi chiêu

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
