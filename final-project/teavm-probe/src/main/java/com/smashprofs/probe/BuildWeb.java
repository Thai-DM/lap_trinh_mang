package com.smashprofs.probe;

import com.github.xpenatan.gdx.teavm.backends.shared.config.AssetFileHandle;
import com.github.xpenatan.gdx.teavm.backends.shared.config.builder.TeaBuilder;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import java.io.File;
import org.teavm.vm.TeaVMOptimizationLevel;

public final class BuildWeb {
    public static void main(String[] args) {
        new TeaBuilder(new WebBackend().setHtmlTitle("SmashProfs - TeaVM trial")
                .setHtmlWidth(1280).setHtmlHeight(720).setStartJettyAfterBuild(false))
                .addAssets(new AssetFileHandle("build/probe-assets"))
                .setMainClass("com.smashprofs.probe.WebLauncher")
                .setOutputName("app")
                .setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE)
                .setObfuscated(false)
                .build(new File("build/dist"));
    }
}
