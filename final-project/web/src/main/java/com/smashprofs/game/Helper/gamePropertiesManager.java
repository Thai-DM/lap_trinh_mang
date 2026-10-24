package com.smashprofs.game.Helper;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.smashprofs.game.Exceptions.NegativeSeconds;
/** Browser storage replaces the desktop properties file; names and values stay compatible. */
public final class gamePropertiesManager {
    private static Preferences prefs() { return Gdx.app.getPreferences("smashprofs-game-info"); }
    public static void firstStart() {
        var p=prefs();
        if(!p.contains(Keys.GAMETIME.getValue())) { p.putString(Keys.GAMETIME.getValue(),"0 min 0 sek"); }
        if(!p.contains(Keys.TIMESPLAYED.getValue())) { p.putString(Keys.TIMESPLAYED.getValue(),"0"); }
        if(!p.contains(Keys.EASTEREGG.getValue())) { p.putString(Keys.EASTEREGG.getValue(),"false"); }
        p.flush();
    }
    public static void edit(Keys key,String value) { prefs().putString(key.getValue(),value).flush(); }
    public static String getEntry(Keys key) { return prefs().getString(key.getValue(),key==Keys.GAMETIME?"0 min 0 sek":key==Keys.TIMESPLAYED?"0":"false"); }
    public static int stringToSeconds(String time) { var p=time.split(" ");return Integer.parseInt(p[0])*60+Integer.parseInt(p[2]); }
    public static String secondsToString(int seconds) throws NegativeSeconds { if(seconds<0) { throw new NegativeSeconds("Seconds must be non-negative"); }return seconds/60+" min "+seconds%60+" sek"; }
}
