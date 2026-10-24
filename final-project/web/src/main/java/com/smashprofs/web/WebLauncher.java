package com.smashprofs.web;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import com.smashprofs.game.Game;
import com.smashprofs.game.Screens.MainMenuScreen;
import org.teavm.jso.JSBody;

public final class WebLauncher {
    public static void main(String[] args) {
        System.setProperty("smashprofs.server.host",gateway());
        System.setProperty("smashprofs.server.port","8090");
        System.setProperty("smashprofs.web.effects",fullEffects()?"true":"false");
        var config=new WebApplicationConfiguration();config.width=0;config.height=0;config.useGL30=false;
        config.powerPreference="default";
        boolean diagnostics=diagnosticsEnabled();
        new WebApplication(new Game() {
            private boolean failed;
            private void fail(Throwable failure) {
                failed=true;com.badlogic.gdx.Gdx.app.error("SmashProfs",failure.toString());showFailure(failure.toString());
            }
            @Override public void create() { try { super.create();setScreen(new MainMenuScreen(this)); }catch(Throwable failure) { fail(failure); } }
            @Override public void render() {
                if(failed || graphicsLost()) { return; }
                try { super.render();if(diagnostics) { BrowserDiagnostics.update(this); }loaded(); }catch(Throwable failure) { fail(failure); }
            }
        },config);
    }
    @JSBody(script="var q=new URLSearchParams(location.search);return q.get('ws')||window.SMASHPROFS_WS_URL||(location.hostname==='localhost'||location.hostname==='127.0.0.1'?'ws://127.0.0.1:8090':(location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws');")
    private static native String gateway();
    @JSBody(script="var q=new URLSearchParams(location.search);return q.get('graphics')!=='compat'&&(q.get('effects')==='full'||window.SMASHPROFS_FULL_EFFECTS===true);") private static native boolean fullEffects();
    @JSBody(script="return window.smashprofsGraphicsLost===true;") private static native boolean graphicsLost();
    @JSBody(script="return new URLSearchParams(location.search).get('debug')==='1';") private static native boolean diagnosticsEnabled();
    @JSBody(script="var e=document.getElementById('loading');if(e)e.hidden=true;") private static native void loaded();
    @JSBody(params={"message"},script="if(window.showFailure)window.showFailure(message);") private static native void showFailure(String message);
}
