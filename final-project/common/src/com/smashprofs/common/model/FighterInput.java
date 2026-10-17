package com.smashprofs.common.model;

/** Held directions/charge plus one-shot edges. No client damage, attack ID or coordinates. */
public record FighterInput(boolean downHeld,boolean jumpHeld,boolean upHeld,boolean heavyPressed,
                           boolean heavyHeld,boolean dodgePressed,boolean throwPressed,boolean dashPressed) {
    public static final FighterInput NONE=new FighterInput(false,false,false,false,false,false,false,false);
    public FighterInput merge(FighterInput latest) {
        return new FighterInput(latest.downHeld,latest.jumpHeld,latest.upHeld,heavyPressed||latest.heavyPressed,
                latest.heavyHeld,dodgePressed||latest.dodgePressed,throwPressed||latest.throwPressed,dashPressed||latest.dashPressed);
    }
    public FighterInput heldOnly() { return new FighterInput(downHeld,jumpHeld,upHeld,false,heavyHeld,false,false,false); }
}
