package com.smashprofs.web;
import com.github.xpenatan.gdx.teavm.backends.shared.config.AssetFileHandle;
import com.github.xpenatan.gdx.teavm.backends.shared.config.builder.TeaBuilder;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import org.teavm.vm.TeaVMOptimizationLevel;
import java.io.File;
import java.nio.file.*;
public final class BuildWeb {
    public static void main(String[] args) throws Exception {
        new TeaBuilder(new WebBackend().setHtmlTitle("Super Smash Profs")
            .setHtmlWidth(1280).setHtmlHeight(720).setStartJettyAfterBuild(false))
            .addAssets(new AssetFileHandle("build/assets"))
            .addAssets(new AssetFileHandle("gdxvfx",com.badlogic.gdx.Files.FileType.Classpath))
            .setMainClass("com.smashprofs.web.WebLauncher").setOutputName("game")
            .setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE).setObfuscated(false)
            .build(new File("build/dist"));
        Files.copy(Path.of("src/main/web/index.html"),Path.of("build/dist/webapp/index.html"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/web/web-config.js"),Path.of("build/dist/webapp/web-config.js"),StandardCopyOption.REPLACE_EXISTING);
    }
}
