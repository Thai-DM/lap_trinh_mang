package com.smashprofs.game.Helper;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
/** Browser platform implementation; logging uses the browser console. */
public final class Util {
    public static int wsAxis() { return Gdx.input.isKeyPressed(Input.Keys.W)?1:Gdx.input.isKeyPressed(Input.Keys.S)?-1:0; }
    public static int adAxis() { return Gdx.input.isKeyPressed(Input.Keys.A)?-1:Gdx.input.isKeyPressed(Input.Keys.D)?1:0; }
    public static int updownAxis() { return Gdx.input.isKeyPressed(Input.Keys.UP)?1:Gdx.input.isKeyPressed(Input.Keys.DOWN)?-1:0; }
    public static int leftrightAxis() { return Gdx.input.isKeyPressed(Input.Keys.LEFT)?-1:Gdx.input.isKeyPressed(Input.Keys.RIGHT)?1:0; }
    public static int getLength() { return Gdx.graphics.getWidth(); }
    public static int getHeight() { return Gdx.graphics.getHeight(); }
    public static int getCentreX() { return getLength()/2; }
    public static int getCentreY() { return getHeight()/2; }
    public static int getRealCentreX(int width) { return getCentreX()-width/2; }
    public static int getRealCentreY(int height) { return getCentreY()-height/2; }
    public static void log4JconfLoad(String ignored) { Gdx.app.log("SmashProfs","Browser logging enabled"); }
}
