package com.smashprofs.game.Actors.Players;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.World;
//import com.smashprofs.game.Actors.Players.Player;
import com.smashprofs.game.Actors.Players.Player;
import com.smashprofs.game.Helper.PlayerTypes;
/**
 * Leo as a game character.
 * Special ability:
 * Placeable landmine.
 */
public class Leo extends Player {

    // Character Stats (Chỉ số nhân vật: thang điểm 1 - 8)
    public static final int STAT_ATTACK    = com.smashprofs.common.model.FighterStats.forCharacter("Leo").attack(); // Sát thương
    public static final int STAT_DEFENSE   = com.smashprofs.common.model.FighterStats.forCharacter("Leo").defense(); // Phòng thủ / Giáp
    public static final int STAT_SPEED     = com.smashprofs.common.model.FighterStats.forCharacter("Leo").speed(); // Tốc độ di chuyển
    public static final int STAT_DEXTERITY = com.smashprofs.common.model.FighterStats.forCharacter("Leo").dexterity(); // Khéo léo / Hồi chiêu

    /**
     * Leo's default constructor. Creates Leo at the set spawnpoint.
     * @param world
     * The world Leo will be created in.
     * @param inputState
     * The controls Leo should listen to.
     * @param spawnpoint
     * The point in the world Leo will spawn in.
     * @param playerName
     * The name Leo will have in the game.
     * @param userData
     * Leo's userData.
     */
    public Leo(World world, Player.InputState inputState, Vector2 spawnpoint, String playerName, String userData) {
        super(world, inputState, spawnpoint, playerName, PlayerTypes.Leo, userData, new Texture("Sprites/Leo/leo_stand.png"), new Texture("Sprites/Leo/leo_run.png"), new Texture("Sprites/Leo/leo_run.png"), new Texture("Sprites/Leo/leo_punch.png"));


    }
}
