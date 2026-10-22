package com.smashprofs.probe;

import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import com.smashprofs.game.Screens.TeaVmProbe;

public final class WebLauncher {
    public static void main(String[] args) {
        var config=new WebApplicationConfiguration();
        config.width=0;config.height=0;config.useGL30=false;
        new WebApplication(new TeaVmProbe(),config);
    }
}
