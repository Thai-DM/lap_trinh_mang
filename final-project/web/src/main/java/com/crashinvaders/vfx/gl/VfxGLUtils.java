package com.crashinvaders.vfx.gl;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.github.xpenatan.gdx.teavm.backends.web.VfxFramebufferAccess;
import java.nio.IntBuffer;

/** TeaVM platform adapter for the VFX 0.5 GL utility API.
 * Direct WebGL framebuffer access replaces the library's reflective GWT-backend lookup.
 * The actual VFX effects, framebuffers, shaders and capture/render pipeline remain unchanged.
 */
public final class VfxGLUtils {
    public static String prependVertexCode="",prependFragmentCode="";
    public static VfxGlExtension glExtension=VfxFramebufferAccess::boundHandle;
    public static boolean enableGLQueryStates=false;
    private static final IntBuffer viewportBuffer=BufferUtils.newIntBuffer(4);
    private static final VfxGlViewport viewport=new VfxGlViewport();
    public static int getBoundFboHandle() { return glExtension.getBoundFboHandle(); }
    public static VfxGlViewport getViewport() {
        viewportBuffer.clear();Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT,viewportBuffer);
        return viewport.set(viewportBuffer.get(0),viewportBuffer.get(1),viewportBuffer.get(2),viewportBuffer.get(3));
    }
    public static ShaderProgram compileShader(FileHandle vertex,FileHandle fragment) { return compileShader(vertex,fragment,""); }
    public static ShaderProgram compileShader(FileHandle vertex,FileHandle fragment,String defines) {
        if(vertex==null||fragment==null||defines==null) { throw new IllegalArgumentException("Shader files and defines are required"); }
        var shader=new ShaderProgram(prependVertexCode+defines+"\n"+vertex.readString(),prependFragmentCode+defines+"\n"+fragment.readString());
        if(!shader.isCompiled()) { String log=shader.getLog();shader.dispose();throw new GdxRuntimeException("VFX shader compile failed: "+log); }
        return shader;
    }
    public static boolean isGLEnabled(int capability) { return enableGLQueryStates && Gdx.gl.glIsEnabled(capability); }
}
