package com.smashprofs.common.model;

/** Shared balance values used by both the roster UI and authoritative simulation. */
public record FighterStats(int attack,int defense,int speed,int dexterity) {
    public static FighterStats forCharacter(String name) {
        return switch(name) {
            case "Alex"->new FighterStats(7,6,5,5);case "Luca"->new FighterStats(6,4,8,6);
            case "Maurice"->new FighterStats(4,4,7,8);case "Leo"->new FighterStats(6,6,6,6);
            case "Viktor"->new FighterStats(8,7,3,4);default->throw new IllegalArgumentException("Character");
        };
    }
}
