package com.github.xpenatan.gdx.teavm.backends.web;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import org.teavm.jso.JSObject;
/** Pinned backend 1.6.2 bridge: WebGL returns an object, VFX needs its actual libGDX integer handle. */
public final class VfxFramebufferAccess {
    public static int boundHandle() {
        var backend=(WebGL20)Gdx.gl20;
        JSObject framebuffer=backend.gl.getParameter(GL20.GL_FRAMEBUFFER_BINDING);
        return framebuffer==null?0:backend.frameBuffers.getKey(framebuffer.cast());
    }
}
