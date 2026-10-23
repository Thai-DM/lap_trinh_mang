package com.smashprofs.web.log;
import com.badlogic.gdx.Gdx;
public final class Logger {
    private final String tag;
    Logger(String tag) { this.tag=tag; }
    private String format(Object message,Object... args) {
        String text=String.valueOf(message);
        for(var arg:args) { int i=text.indexOf("{}");if(i<0) { break; }text=text.substring(0,i)+arg+text.substring(i+2); }return text;
    }
    public void info(Object message,Object... args) { if(Gdx.app!=null) { Gdx.app.log(tag,format(message,args)); } }
    public void debug(Object message,Object... args) { if(Gdx.app!=null) { Gdx.app.debug(tag,format(message,args)); } }
    public void warn(Object message,Object... args) { info(message,args); }
    public void error(Object message,Object... args) { if(Gdx.app!=null) { Gdx.app.error(tag,format(message,args)); } }
}
