/**
 * Copyright 2012 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */

package com.jogamp.opengl;

import java.util.Arrays;

import javax.media.opengl.GL;
import javax.media.opengl.GL2ES2;
import javax.media.opengl.GL2ES3;
import javax.media.opengl.GL2GL3;
import javax.media.opengl.GL3;
import javax.media.opengl.GLBase;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLContext;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;

import jogamp.opengl.Debug;

import com.jogamp.common.util.PropertyAccess;
import com.jogamp.opengl.FBObject.Attachment.Type;

/**
 * Core utility class simplifying usage of framebuffer objects (FBO)
 * with all {@link GLProfile}s.
 * <p>
 * Supports on-the-fly reconfiguration of dimension and multisample buffers via {@link #reset(GL, int, int, int, boolean)}
 * while preserving the {@link Attachment} references.
 * </p>
 * <p>
 * Integrates default read/write framebuffers via {@link GLContext#getDefaultReadFramebuffer()} and {@link GLContext#getDefaultReadFramebuffer()},
 * which is being hooked at {@link GL#glBindFramebuffer(int, int)} when the default (<code>zero</code>) framebuffer is selected.
 * </p>
 *
 * <p>FIXME: Implement support for {@link Type#DEPTH_TEXTURE}, {@link Type#STENCIL_TEXTURE} .</p>
 */
public class FBObject {
    protected static final boolean DEBUG;
    private static final int USER_MAX_TEXTURE_SIZE;
    private static final boolean FBOResizeQuirk = false;

    static {
        Debug.initSingleton();
        DEBUG = Debug.debug("FBObject");
        USER_MAX_TEXTURE_SIZE = PropertyAccess.getIntProperty("jogl.debug.FBObject.MaxTextureSize", true, 0);
    }

    private static enum DetachAction { NONE, DISPOSE, RECREATE };

    /**
     * Generic color buffer FBO attachment, either of type {@link ColorAttachment} or {@link TextureAttachment}.
     * <p>Always an instance of {@link Attachment}.</p>
     */
    public static interface Colorbuffer {
        /**
         * Initializes the color buffer and set it's parameter, if uninitialized, i.e. name is <code>zero</code>.
         * @return <code>true</code> if newly initialized, otherwise <code>false</code>.
         * @throws GLException if buffer generation or setup fails. The just created buffer name will be deleted in this case.
         */
        boolean initialize(final GL gl) throws GLException;

        /**
         * Releases the color buffer if initialized, i.e. name is not <code>zero</code>.
         * @throws GLException if buffer release fails.
         */
        void free(final GL gl) throws GLException;

        /**
         * Writes the internal format to the given GLCapabilities object.
         * @param caps the destination for format bits
         * @param rgba8Avail whether rgba8 is available
         */
        void formatToGLCapabilities(final GLCapabilities caps, final boolean rgba8Avail);

        /**
         * Returns <code>true</code> if instance is of type {@link TextureAttachment}
         * and <code>false</code> if instance is of type {@link ColorAttachment}.
         */
        boolean isTextureAttachment();

        /**
         * Casts this object to a {@link TextureAttachment} reference, see {@link #isTextureAttachment()}.
         * @throws GLException if this object is not of type {@link TextureAttachment}
         * @see #isTextureAttachment()
         */
        TextureAttachment getTextureAttachment();

        /**
         * Casts this object to a {@link ColorAttachment} reference, see {@link #isTextureAttachment()}.
         * @throws GLException if this object is not of type {@link ColorAttachment}
         * @see #isTextureAttachment()
         */
        ColorAttachment getColorAttachment();

        /** internal format of colorbuffer */
        int getFormat();

        /** width of colorbuffer */
        int getWidth();

        /** height of colorbuffer */
        int getHeight();

        /** colorbuffer name [1..max] */
        int getName();
    }

    /** Common super class of all FBO attachments */
    public static abstract class Attachment {
        public enum Type {
            NONE, DEPTH, STENCIL, DEPTH_STENCIL, COLOR, COLOR_TEXTURE, DEPTH_TEXTURE, STENCIL_TEXTURE;

            /**
             * Returns {@link #COLOR}, {@link #DEPTH}, {@link #STENCIL} or {@link #DEPTH_STENCIL}
             * @throws IllegalArgumentException if <code>format</code> cannot be handled.
             */
            public static Type determine(final int format) throws IllegalArgumentException {
                switch(format) {
                    case GL.GL_RGBA4:
                    case GL.GL_RGB5_A1:
                    case GL.GL_RGB565:
                    case GL.GL_RGB8:
                    case GL.GL_RGBA8:
                        return Type.COLOR;
                    case GL.GL_DEPTH_COMPONENT16:
                    case GL.GL_DEPTH_COMPONENT24:
                    case GL.GL_DEPTH_COMPONENT32:
                        return Type.DEPTH;
                    case GL.GL_STENCIL_INDEX1:
                    case GL.GL_STENCIL_INDEX4:
                    case GL.GL_STENCIL_INDEX8:
                        return Type.STENCIL;
                    case GL.GL_DEPTH24_STENCIL8:
                        return Type.DEPTH_STENCIL;
                    default:
                        throw new IllegalArgumentException("format invalid: "+toHexString(format));
                }
            }
        };

        /** immutable type [{@link #COLOR}, {@link #DEPTH}, {@link #STENCIL}, {@link #COLOR_TEXTURE}, {@link #DEPTH_TEXTURE}, {@link #STENCIL_TEXTURE} ] */
        public final Type type;

        /** immutable the internal format */
        public final int format;

        private int width, height;

        private int name;

        protected Attachment(final Type type, final int iFormat, final int width, final int height, final int name) {
            this.type = type;
            this.format = iFormat;
            this.width = width;
            this.height = height;
            this.name = name;
        }

        /**
         * Writes the internal format to the given GLCapabilities object.
         * @param caps the destination for format bits
         * @param rgba8Avail whether rgba8 is available
         */
        public final void formatToGLCapabilities(final GLCapabilities caps, final boolean rgba8Avail) {
            final int _format;
            switch(format) {
                case GL.GL_RGBA:
                case 4:
                    _format = rgba8Avail ? GL.GL_RGBA8 : GL.GL_RGBA4;
                    break;
                case GL.GL_RGB:
                case 3:
                    _format = rgba8Avail ? GL.GL_RGB8 : GL.GL_RGB565;
                    break;
                default:
                    _format = format;
            }
            switch(_format) {
                case GL.GL_RGBA4:
                    caps.setRedBits(4);
                    caps.setGreenBits(4);
                    caps.setBlueBits(4);
                    caps.setAlphaBits(4);
                    break;
                case GL.GL_RGB5_A1:
                    caps.setRedBits(5);
                    caps.setGreenBits(5);
                    caps.setBlueBits(5);
                    caps.setAlphaBits(1);
                    break;
                case GL.GL_RGB565:
                    caps.setRedBits(5);
                    caps.setGreenBits(6);
                    caps.setBlueBits(5);
                    caps.setAlphaBits(0);
                    break;
                case GL.GL_RGB8:
                    caps.setRedBits(8);
                    caps.setGreenBits(8);
                    caps.setBlueBits(8);
                    caps.setAlphaBits(0);
                    break;
                case GL.GL_RGBA8:
                    caps.setRedBits(8);
                    caps.setGreenBits(8);
                    caps.setBlueBits(8);
                    caps.setAlphaBits(8);
                    break;
                case GL.GL_DEPTH_COMPONENT16:
                    caps.setDepthBits(16);
                    break;
                case GL.GL_DEPTH_COMPONENT24:
                    caps.setDepthBits(24);
                    break;
                case GL.GL_DEPTH_COMPONENT32:
                    caps.setDepthBits(32);
                    break;
                case GL.GL_STENCIL_INDEX1:
                    caps.setStencilBits(1);
                    break;
                case GL.GL_STENCIL_INDEX4:
                    caps.setStencilBits(4);
                    break;
                case GL.GL_STENCIL_INDEX8:
                    caps.setStencilBits(8);
                    break;
                case GL.GL_DEPTH24_STENCIL8:
                    caps.setDepthBits(24);
                    caps.setStencilBits(8);
                    break;
                default:
                    throw new IllegalArgumentException("format invalid: "+toHexString(format));
            }
        }

        /** immutable internal format of attachment */
        public final int getFormat() { return format; }

        /** width of attachment */
        public final int getWidth() { return width; }
        /** height of attachment */
        public final int getHeight() { return height; }
        /* pp */ final void setSize(final int w, final int h) { width = w; height = h; }

        /** buffer name [1..max], maybe a texture or renderbuffer name, depending on type. */
        public final int getName() { return name; }
        /* pp */ final void setName(final int n) { name = n; }

        /**
         * Initializes the attachment and set it's parameter, if uninitialized, i.e. name is <code>zero</code>.
         * <pre>
            final boolean init = 0 == name;
            if( init ) {
                do init ..
            }
            return init;
         * </pre>
         * @return <code>true</code> if newly initialized, otherwise <code>false</code>.
         * @throws GLException if buffer generation or setup fails. The just created buffer name will be deleted in this case.
         */
        public abstract boolean initialize(final GL gl) throws GLException;

        /**
         * Releases the attachment if initialized, i.e. name is not <code>zero</code>.
         * <pre>
            if(0 != name) {
                do free ..
                name = 0;
            }
         * </pre>
         * @throws GLException if buffer release fails.
         */
        public abstract void free(final GL gl) throws GLException;

        /**
         * <p>
         * Comparison by {@link #type}, {@link #format}, {@link #width}, {@link #height} and {@link #name}.
         * </p>
         * {@inheritDoc}
         */
        @Override
        public boolean equals(final Object o) {
            if( this == o ) return true;
            if( ! ( o instanceof Attachment ) ) return false;
            final Attachment a = (Attachment)o;
            return type == a.type &&
                   format == a.format &&
                   width == a.width   &&
                   height== a.height  &&
                   name == a.name     ;
        }

        /**
         * <p>
         * Hashed by {@link #type}, {@link #format}, {@link #width}, {@link #height} and {@link #name}.
         * </p>
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            // 31 * x == (x << 5) - x
            int hash = 31 + type.ordinal();
            hash = ((hash << 5) - hash) + format;
            hash = ((hash << 5) - hash) + width;
            hash = ((hash << 5) - hash) + height;
            hash = ((hash << 5) - hash) + name;
            return hash;
        }

        int objectHashCode() { return super.hashCode(); }

        @Override
        public String toString() {
            return getClass().getSimpleName()+"[type "+type+", format "+toHexString(format)+", "+width+"x"+height+
                   "; name "+toHexString(name)+", obj "+toHexString(objectHashCode())+"]";
        }

        public static Type getType(final int attachmentPoint, final int maxColorAttachments) {
            if( GL.GL_COLOR_ATTACHMENT0 <= attachmentPoint && attachmentPoint < GL.GL_COLOR_ATTACHMENT0+maxColorAttachments ) {
                return Type.COLOR;
            }
            switch(attachmentPoint) {
                case GL.GL_DEPTH_ATTACHMENT:
                    return Type.DEPTH;
                case GL.GL_STENCIL_ATTACHMENT:
                    return Type.STENCIL;
                default:
                    throw new IllegalArgumentException("Invalid attachment point "+toHexString(attachmentPoint));
            }
        }
    }

    /** Other renderbuffer attachment which maybe a colorbuffer, depth or stencil. */
    public static class RenderAttachment extends Attachment {
        private int samples;

        /**
         * @param type allowed types are {@link Type#DEPTH_STENCIL} {@link Type#DEPTH}, {@link Type#STENCIL} or {@link Type#COLOR}
         * @param iFormat
         * @param samples
         * @param width
         * @param height
         * @param name
         */
        public RenderAttachment(final Type type, final int iFormat, final int samples, final int width, final int height, final int name) {
            super(validateType(type), iFormat, width, height, name);
            this.samples = samples;
        }

        /** number of samples, or zero for no multisampling */
        public final int getSamples() { return samples; }
        /* pp */ final void setSamples(final int s) { samples = s; }

        private static Type validateType(final Type type) {
            switch(type) {
                case DEPTH_STENCIL:
                case DEPTH:
                case STENCIL:
                case COLOR:
                    return type;
                default:
                    throw new IllegalArgumentException("Invalid type: "+type);
            }
        }

        /**
         * <p>
         * Comparison by {@link #type}, {@link #format}, {@link #samples}, {@link #width}, {@link #height} and {@link #name}.
         * </p>
         * {@inheritDoc}
         */
        @Override
        public boolean equals(final Object o) {
            if( this == o ) return true;
            if( ! ( o instanceof RenderAttachment ) ) return false;
            return super.equals(o) &&
                   samples == ((RenderAttachment)o).samples;
        }

        /**
         * <p>
         * Hashed by {@link #type}, {@link #format}, {@link #samples}, {@link #width}, {@link #height} and {@link #name}.
         * </p>
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            // 31 * x == (x << 5) - x
            int hash = super.hashCode();
            hash = ((hash << 5) - hash) + samples;
            return hash;
        }

        @Override
        public boolean initialize(final GL gl) throws GLException {
            final boolean init = 0 == getName();
            if( init ) {
                checkPreGLError(gl);

                final int[] name = new int[] { -1 };
                gl.glGenRenderbuffers(1, name, 0);
                setName(name[0]);

                gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, getName());
                if( samples > 0 ) {
                    ((GL2ES3)gl).glRenderbufferStorageMultisample(GL.GL_RENDERBUFFER, samples, format, getWidth(), getHeight());
                } else {
                    gl.glRenderbufferStorage(GL.GL_RENDERBUFFER, format, getWidth(), getHeight());
                }
                final int glerr = gl.glGetError();
                if(GL.GL_NO_ERROR != glerr) {
                    gl.glDeleteRenderbuffers(1, name, 0);
                    setName(0);
                    throw new GLException("GL Error "+toHexString(glerr)+" while creating "+this);
                }
                if(DEBUG) {
                    System.err.println("Attachment.init.X: "+this);
                }
            }
            return init;
        }

        @Override
        public void free(final GL gl) {
            final int[] name = new int[] { getName() };
            if( 0 != name[0] ) {
                if(DEBUG) {
                    System.err.println("Attachment.free.0: "+this);
                }
                gl.glDeleteRenderbuffers(1, name, 0);
                setName(0);
            }
        }

        @Override
        public String toString() {
            return getClass().getSimpleName()+"[type "+type+", format "+toHexString(format)+", samples "+samples+", "+getWidth()+"x"+getHeight()+
                   ", name "+toHexString(getName())+", obj "+toHexString(objectHashCode())+"]";
        }
    }

    /** Color render buffer FBO attachment  */
    public static class ColorAttachment extends RenderAttachment implements Colorbuffer {
        public ColorAttachment(final int iFormat, final int samples, final int width, final int height, final int name) {
            super(Type.COLOR, iFormat, samples, width, height, name);
        }
        @Override
        public final boolean isTextureAttachment() { return false; }
        @Override
        public final TextureAttachment getTextureAttachment() { throw new GLException("Not a TextureAttachment, but ColorAttachment"); }
        @Override
        public final ColorAttachment getColorAttachment() { return this; }

    }

    /** Texture FBO attachment */
    public static class TextureAttachment extends Attachment implements Colorbuffer  {
        /** details of the texture setup */
        public final int dataFormat, dataType, magFilter, minFilter, wrapS, wrapT;

        /**
         * @param type allowed types are [ {@link Type#COLOR_TEXTURE}, {@link Type#DEPTH_TEXTURE}, {@link Type#STENCIL_TEXTURE} ]
         * @param iFormat
         * @param width
         * @param height
         * @param dataFormat
         * @param dataType
         * @param magFilter
         * @param minFilter
         * @param wrapS
         * @param wrapT
         * @param name
         */
        public TextureAttachment(final Type type, final int iFormat, final int width, final int height, final int dataFormat, final int dataType,
                                 final int magFilter, final int minFilter, final int wrapS, final int wrapT, final int name) {
            super(validateType(type), iFormat, width, height, name);
            this.dataFormat = dataFormat;
            this.dataType = dataType;
            this.magFilter = magFilter;
            this.minFilter = minFilter;
            this.wrapS = wrapS;
            this.wrapT = wrapT;
        }

        private static Type validateType(final Type type) {
            switch(type) {
                case COLOR_TEXTURE:
                case DEPTH_TEXTURE:
                case STENCIL_TEXTURE:
                    return type;
                default:
                    throw new IllegalArgumentException("Invalid type: "+type);
            }
        }

        /**
         * Initializes the texture and set it's parameter, if uninitialized, i.e. name is <code>zero</code>.
         * @throws GLException if texture generation and setup fails. The just created texture name will be deleted in this case.
         */
        @Override
        public boolean initialize(final GL gl) throws GLException {
            final boolean init = 0 == getName();
            if( init ) {
                checkPreGLError(gl);

                final int[] name = new int[] { -1 };
                gl.glGenTextures(1, name, 0);
                if(0 == name[0]) {
                    throw new GLException("null texture, "+this);
                }
                setName(name[0]);

                gl.glBindTexture(GL.GL_TEXTURE_2D, name[0]);
                if( 0 < magFilter ) {
                    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, magFilter);
                }
                if( 0 < minFilter ) {
                    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, minFilter);
                }
                if( 0 < wrapS ) {
                    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, wrapS);
                }
                if( 0 < wrapT ) {
                    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, wrapT);
                }
                boolean preTexImage2D = true;
                int glerr = gl.glGetError();
                if(GL.GL_NO_ERROR == glerr) {
                    preTexImage2D = false;
                    gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, format, getWidth(), getHeight(), 0, dataFormat, dataType, null);
                    glerr = gl.glGetError();
                }
                if(GL.GL_NO_ERROR != glerr) {
                    gl.glDeleteTextures(1, name, 0);
                    setName(0);
                    throw new GLException("GL Error "+toHexString(glerr)+" while creating (pre TexImage2D "+preTexImage2D+") "+this);
                }
                if(DEBUG) {
                    System.err.println("Attachment.init.X: "+this);
                }
            }
            return init;
        }

        @Override
        public void free(final GL gl) {
            final int[] name = new int[] { getName() };
            if( 0 != name[0] ) {
                if(DEBUG) {
                    System.err.println("Attachment.free.0: "+this);
                }
                gl.glDeleteTextures(1, name, 0);
                setName(0);
            }
        }

        @Override
        public final boolean isTextureAttachment() { return true; }
        @Override
        public final TextureAttachment getTextureAttachment() { return this; }
        @Override
        public final ColorAttachment getColorAttachment() { throw new GLException("Not a ColorAttachment, but TextureAttachment"); }

        @Override
        public String toString() {
            return getClass().getSimpleName()+"[type "+type+", target GL_TEXTURE_2D, level 0, format "+toHexString(format)+
                                              ", "+getWidth()+"x"+getHeight()+", border 0, dataFormat "+toHexString(dataFormat)+
                                              ", dataType "+toHexString(dataType)+
                                              "; min/mag "+toHexString(minFilter)+"/"+toHexString(magFilter)+
                                              ", wrap S/T "+toHexString(wrapS)+"/"+toHexString(wrapT)+
                                              "; name "+toHexString(getName())+", obj "+toHexString(objectHashCode())+"]";
        }
    }
    static String toHexString(final int v) {
        return "0x"+Integer.toHexString(v);
    }

    /**
     * Creates a color {@link TextureAttachment}, i.e. type {@link Type#COLOR_TEXTURE},
     * selecting the texture data type and format automatically.
     *
     * <p>Using default min/mag filter {@link GL#GL_NEAREST} and default wrapS/wrapT {@link GL#GL_CLAMP_TO_EDGE}.</p>
     *
     * @param gl the used {@link GLContext}'s {@link GL} object
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @param width texture width
     * @param height texture height
     * @return the created and uninitialized color {@link TextureAttachment}
     */
    public static final TextureAttachment createColorTextureAttachment(final GL gl, final boolean alpha, final int width, final int height) {
        return createColorTextureAttachment(gl, alpha, width, height, GL.GL_NEAREST, GL.GL_NEAREST, GL.GL_CLAMP_TO_EDGE, GL.GL_CLAMP_TO_EDGE);
    }

    /**
     * Creates a color {@link TextureAttachment}, i.e. type {@link Type#COLOR_TEXTURE},
     * selecting the texture data type and format automatically.
     * <p>
     * For GLES3, sampling-sink format <b>must be equal</b> w/ the sampling-source {@link Colorbuffer},
     * see details below. Implementation aligns w/ {@link #createColorAttachment(boolean)}
     * and is enforced via {@link #sampleSinkFormatMismatch(GL)}.
     * </p>
     * <p>
     * ES3 BlitFramebuffer Requirements: OpenGL ES 3.0.2 p194: 4.3.2  Copying Pixels
     * <pre>
     * If SAMPLE_BUFFERS for the read framebuffer is greater than zero, no copy
     * is performed and an INVALID_OPERATION error is generated if the formats of
     * the read and draw framebuffers are not identical or if the source and destination
     * rectangles are not defined with the same (X0, Y 0) and (X1, Y 1) bounds.
     * </pre>
     * Texture and Renderbuffer format details:
     * <pre>
     * ES2 Base iFormat: OpenGL ES 2.0.24 p66: 3.7.1 Texture Image Specification, Table 3.8
     *   ALPHA, LUMINANCE, LUMINANCE_ALPHA, RGB, RGBA
     *
     * ES3 Base iFormat: OpenGL ES 3.0.2 p125: 3.8.3 Texture Image Specification, Table 3.11
     *   ALPHA, LUMINANCE, LUMINANCE_ALPHA, RGB, RGBA
     *   DEPTH_COMPONENT, STENCIL_COMPONENT, RED, RG
     *
     * ES3 Required Texture and Renderbuffer iFormat: OpenGL ES 3.0.2 p126: 3.8.3 Texture Image Specification
     *   - RGBA32I, RGBA32UI, RGBA16I, RGBA16UI, RGBA8, RGBA8I,
     *     RGBA8UI, SRGB8_ALPHA8, RGB10_A2, RGB10_A2UI, RGBA4, and
     *     RGB5_A1.
     *   - RGB8 and RGB565.
     *   - RG32I, RG32UI, RG16I, RG16UI, RG8, RG8I, and RG8UI.
     *   - R32I, R32UI, R16I, R16UI, R8, R8I, and R8UI.
     * </pre>
     * </p>
     *
     * @param gl the used {@link GLContext}'s {@link GL} object
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @param width texture width
     * @param height texture height
     * @param magFilter if > 0 value for {@link GL#GL_TEXTURE_MAG_FILTER}
     * @param minFilter if > 0 value for {@link GL#GL_TEXTURE_MIN_FILTER}
     * @param wrapS if > 0 value for {@link GL#GL_TEXTURE_WRAP_S}
     * @param wrapT if > 0 value for {@link GL#GL_TEXTURE_WRAP_T}
     * @return the created and uninitialized color {@link TextureAttachment}
     */
    public static final TextureAttachment createColorTextureAttachment(final GL gl, final boolean alpha, final int width, final int height,
                                                                       final int magFilter, final int minFilter, final int wrapS, final int wrapT) {
        final int textureInternalFormat, textureDataFormat, textureDataType;
        if(gl.isGLES3()) {
            textureInternalFormat = alpha ? GL.GL_RGBA8 : GL.GL_RGB8;
            textureDataFormat = alpha ? GL.GL_RGBA : GL.GL_RGB;
            textureDataType = GL.GL_UNSIGNED_BYTE;
        } else if(gl.isGLES()) {
            textureInternalFormat = alpha ? GL.GL_RGBA : GL.GL_RGB;
            textureDataFormat = alpha ? GL.GL_RGBA : GL.GL_RGB;
            textureDataType = GL.GL_UNSIGNED_BYTE;
        } else {
            textureInternalFormat = alpha ? GL.GL_RGBA8 : GL.GL_RGB8;
            // textureInternalFormat = alpha ? GL.GL_RGBA : GL.GL_RGB;
            // textureInternalFormat = alpha ? 4 : 3;
            textureDataFormat = alpha ? GL.GL_BGRA : GL.GL_RGB;
            textureDataType = alpha ? GL2GL3.GL_UNSIGNED_INT_8_8_8_8_REV : GL.GL_UNSIGNED_BYTE;
        }
        return createColorTextureAttachment(textureInternalFormat, width, height, textureDataFormat, textureDataType, magFilter, minFilter, wrapS, wrapT);
    }

    /**
     * Creates a color {@link TextureAttachment}, i.e. type {@link Type#COLOR_TEXTURE}.
     *
     * @param internalFormat internalFormat parameter to {@link GL#glTexImage2D(int, int, int, int, int, int, int, int, long)}
     * @param width texture width
     * @param height texture height
     * @param dataFormat format parameter to {@link GL#glTexImage2D(int, int, int, int, int, int, int, int, long)}
     * @param dataType type parameter to {@link GL#glTexImage2D(int, int, int, int, int, int, int, int, long)}
     * @param magFilter if > 0 value for {@link GL#GL_TEXTURE_MAG_FILTER}
     * @param minFilter if > 0 value for {@link GL#GL_TEXTURE_MIN_FILTER}
     * @param wrapS if > 0 value for {@link GL#GL_TEXTURE_WRAP_S}
     * @param wrapT if > 0 value for {@link GL#GL_TEXTURE_WRAP_T}
     * @return the created and uninitialized color {@link TextureAttachment}
     */
    public static final TextureAttachment createColorTextureAttachment(final int internalFormat, final int width, final int height, final int dataFormat, final int dataType,
                                                                       final int magFilter, final int minFilter, final int wrapS, final int wrapT) {
        return new TextureAttachment(Type.COLOR_TEXTURE, internalFormat, width, height, dataFormat, dataType,
                                     magFilter, minFilter, wrapS, wrapT, 0 /* name */);
    }

    private static boolean hasAlpha(final int format) {
        switch(format) {
            case GL.GL_RGBA8:
            case GL.GL_RGBA4:
            case GL.GL_RGBA:
            case GL.GL_BGRA:
            case 4:
                return true;
            default:
                return false;
        }
    }

    private boolean initialized;
    private boolean fullFBOSupport;
    private boolean rgba8Avail;
    private boolean depth24Avail;
    private boolean depth32Avail;
    private boolean stencil01Avail;
    private boolean stencil04Avail;
    private boolean stencil08Avail;
    private boolean stencil16Avail;
    private boolean packedDepthStencilAvail;
    private int maxColorAttachments, maxSamples, maxTextureSize, maxRenderbufferSize;

    private int width, height, samples;
    private int vStatus;
    private boolean ignoreStatus;
    private int fbName;
    private boolean bound;

    private int colorbufferCount;
    private int textureAttachmentCount;
    private Colorbuffer[] colorbufferAttachments; // colorbuffer attachment points
    private RenderAttachment depth, stencil; // depth and stencil maybe equal in case of packed-depth-stencil

    private FBObject samplingSink; // MSAA sink
    private Colorbuffer samplingColorSink;
    private boolean samplingSinkDirty;

    //
    // ColorAttachment helper ..
    //

    private final void validateColorAttachmentPointRange(final int point) {
        if(!initialized) {
            throw new GLException("FBO not initialized");
        }
        if(maxColorAttachments != colorbufferAttachments.length) {
            throw new InternalError("maxColorAttachments "+maxColorAttachments+", array.length "+colorbufferAttachments.length);
        }
        if(0 > point || point >= maxColorAttachments) {
            throw new IllegalArgumentException("attachment point out of range: "+point+", should be within [0.."+(maxColorAttachments-1)+"], "+this);
        }
    }

    private final void validateAddColorAttachment(final int point, final Colorbuffer ca) {
        validateColorAttachmentPointRange(point);
        if( null != colorbufferAttachments[point] ) {
            throw new IllegalArgumentException("Cannot attach "+ca+", attachment point already in use by "+colorbufferAttachments[point]+", "+this);
        }
    }

    private final void addColorAttachment(final int point, final Colorbuffer ca) {
        validateColorAttachmentPointRange(point);
        final Colorbuffer c = colorbufferAttachments[point];
        if( null != c && c != ca ) {
            throw new IllegalArgumentException("Add failed: requested to add "+ca+" at "+point+", but slot is holding "+c+"; "+this);
        }
        colorbufferAttachments[point] = ca;
        colorbufferCount++;
        if( ca.isTextureAttachment() ) {
            textureAttachmentCount++;
        }
    }

    private final void removeColorAttachment(final int point, final Colorbuffer ca) {
        validateColorAttachmentPointRange(point);
        final Colorbuffer c = colorbufferAttachments[point];
        if( null != c && c != ca ) {
            throw new IllegalArgumentException("Remove failed: requested to removed "+ca+" at "+point+", but slot is holding "+c+"; "+this);
        }
        colorbufferAttachments[point] = null;
        colorbufferCount--;
        if( ca.isTextureAttachment() ) {
            textureAttachmentCount--;
        }
    }

    /**
     * Return the {@link Colorbuffer} attachment at <code>attachmentPoint</code> if it is attached to this FBO, otherwise null.
     *
     * @see #attachColorbuffer(GL, boolean)
     * @see #attachColorbuffer(GL, boolean)
     * @see #attachTexture2D(GL, int, boolean, int, int, int, int)
     * @see #attachTexture2D(GL, int, int, int, int, int, int, int, int)
     */
    public final Colorbuffer getColorbuffer(final int attachmentPoint) {
        validateColorAttachmentPointRange(attachmentPoint);
        return colorbufferAttachments[attachmentPoint];
    }

    /**
     * Finds the passed {@link Colorbuffer} within the valid range of attachment points
     * using <i>reference</i> comparison only.
     * <p>
     * Note: Slow. Implementation uses a logN array search to save resources, i.e. not using a HashMap.
     * </p>
     * @param ca the {@link Colorbuffer} to look for.
     * @return -1 if the {@link Colorbuffer} could not be found, otherwise [0..{@link #getMaxColorAttachments()}-1]
     */
    public final int getColorbufferAttachmentPoint(final Colorbuffer ca) {
        for(int i=0; i<colorbufferAttachments.length; i++) {
            if( colorbufferAttachments[i] == ca ) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the passed {@link Colorbuffer} if it is attached to this FBO, otherwise null.
     * Implementation compares the <i>reference</i> only.
     *
     * <p>
     * Note: Slow. Uses {@link #getColorbufferAttachmentPoint(Colorbuffer)} to determine it's attachment point
     *       to be used for {@link #getColorbuffer(int)}
     * </p>
     *
     * @see #attachColorbuffer(GL, boolean)
     * @see #attachColorbuffer(GL, boolean)
     * @see #attachTexture2D(GL, int, boolean, int, int, int, int)
     * @see #attachTexture2D(GL, int, int, int, int, int, int, int, int)
     */
    public final Colorbuffer getColorbuffer(final Colorbuffer ca) {
        final int p = getColorbufferAttachmentPoint(ca);
        return p>=0 ? getColorbuffer(p) : null;
    }

    /**
     * Returns true if any attached {@link Colorbuffer} uses alpha,
     * otherwise false.
     */
    public final boolean hasAttachmentUsingAlpha() {
        final int caCount = getColorbufferCount();
        boolean hasAlpha = false;
        for(int i=0; i<caCount; i++) {
            final Attachment ca = (Attachment)getColorbuffer(i);
            if( null == ca ) {
                break;
            }
            if( hasAlpha(ca.format) ) {
                hasAlpha = true;
                break;
            }
        }
        return hasAlpha;
    }

    /**
     * Creates an uninitialized FBObject instance.
     * <p>
     * Call {@link #init(GL, int, int, int)} .. etc to use it.
     * </p>
     */
    public FBObject() {
        this.initialized = false;

        // TBD @ init
        this.fullFBOSupport = false;
        this.rgba8Avail = false;
        this.depth24Avail = false;
        this.depth32Avail = false;
        this.stencil01Avail = false;
        this.stencil04Avail = false;
        this.stencil08Avail = false;
        this.stencil16Avail = false;
        this.packedDepthStencilAvail = false;
        this.maxColorAttachments=-1;
        this.maxSamples=-1;
        this.maxTextureSize = 0;
        this.maxRenderbufferSize = 0;

        this.width = 0;
        this.height = 0;
        this.samples = 0;
        this.vStatus = -1;
        this.ignoreStatus = false;
        this.fbName = 0;
        this.bound = false;

        this.colorbufferAttachments = null; // at init ..
        this.colorbufferCount = 0;
        this.textureAttachmentCount = 0;
        this.depth = null;
        this.stencil = null;

        this.samplingSink = null;
        this.samplingColorSink = null;
        this.samplingSinkDirty = true;
    }

    private void init(final GL gl, int width, int height, final int samples) throws GLException {
        if(initialized) {
            throw new GLException("FBO already initialized");
        }
        if( !gl.hasBasicFBOSupport() ) {
            throw new GLException("FBO not supported w/ context: "+gl.getContext()+", "+this);
        }
        fullFBOSupport = gl.hasFullFBOSupport();

        rgba8Avail = gl.isGL2ES3() || gl.isExtensionAvailable(GLExtensions.OES_rgb8_rgba8);
        depth24Avail = fullFBOSupport || gl.isExtensionAvailable(GLExtensions.OES_depth24);
        depth32Avail = fullFBOSupport || gl.isExtensionAvailable(GLExtensions.OES_depth32);
        stencil01Avail = fullFBOSupport || gl.isExtensionAvailable(GLExtensions.OES_stencil1);
        stencil04Avail = fullFBOSupport || gl.isExtensionAvailable(GLExtensions.OES_stencil4);
        stencil08Avail = fullFBOSupport || gl.isExtensionAvailable(GLExtensions.OES_stencil8);
        stencil16Avail = fullFBOSupport;

        packedDepthStencilAvail = fullFBOSupport ||
                                  gl.isExtensionAvailable(GLExtensions.OES_packed_depth_stencil) ||
                                  gl.isExtensionAvailable(GLExtensions.EXT_packed_depth_stencil) ;

        final boolean NV_fbo_color_attachments = gl.isExtensionAvailable(GLExtensions.NV_fbo_color_attachments);

        final int val[] = new int[1];

        checkPreGLError(gl);

        int realMaxColorAttachments = 1;
        maxColorAttachments = 1;
        if( fullFBOSupport || NV_fbo_color_attachments ) {
            try {
                val[0] = 0;
                gl.glGetIntegerv(GL2ES2.GL_MAX_COLOR_ATTACHMENTS, val, 0);
                realMaxColorAttachments = 1 <= val[0] ? val[0] : 1; // cap minimum to 1
            } catch (final GLException gle) { gle.printStackTrace(); }
        }
        maxColorAttachments = realMaxColorAttachments <= 8 ? realMaxColorAttachments : 8; // cap to limit array size

        colorbufferAttachments = new Colorbuffer[maxColorAttachments];
        colorbufferCount = 0;
        textureAttachmentCount = 0;

        maxSamples = gl.getMaxRenderbufferSamples();
        gl.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, val, 0);
        final int _maxTextureSize = val[0];
        if( 0 < USER_MAX_TEXTURE_SIZE ) {
            maxTextureSize = USER_MAX_TEXTURE_SIZE;
        } else {
            maxTextureSize = _maxTextureSize;
        }
        gl.glGetIntegerv(GL.GL_MAX_RENDERBUFFER_SIZE, val, 0);
        maxRenderbufferSize = val[0];

        checkPreGLError(gl);

        if( 0 >= width )  { width = 1; }
        if( 0 >= height ) { height = 1; }
        this.width = width;
        this.height = height;
        this.samples = samples <= maxSamples ? samples : maxSamples;

        if(DEBUG) {
            System.err.println("FBObject.init() START: "+width+"x"+height+", "+samples+" -> "+this.samples+" samples");
            System.err.println("fullFBOSupport:           "+fullFBOSupport);
            System.err.println("maxColorAttachments:      "+maxColorAttachments+"/"+realMaxColorAttachments+" [capped/real]");
            System.err.println("maxSamples:               "+maxSamples);
            System.err.println("maxTextureSize:           "+_maxTextureSize+" -> "+maxTextureSize);
            System.err.println("maxRenderbufferSize:      "+maxRenderbufferSize);
            System.err.println("rgba8:                    "+rgba8Avail);
            System.err.println("depth24:                  "+depth24Avail);
            System.err.println("depth32:                  "+depth32Avail);
            System.err.println("stencil01:                "+stencil01Avail);
            System.err.println("stencil04:                "+stencil04Avail);
            System.err.println("stencil08:                "+stencil08Avail);
            System.err.println("stencil16:                "+stencil16Avail);
            System.err.println("packedDepthStencil:       "+packedDepthStencilAvail);
            System.err.println("NV_fbo_color_attachments: "+NV_fbo_color_attachments);
            System.err.println(gl.getContext().getGLVersion());
            System.err.println(JoglVersion.getGLStrings(gl, null, false).toString());
        }

        checkNoError(null, gl.glGetError(), "FBObject Init.pre"); // throws GLException if error

        if( textureAttachmentCount > 0 && ( width > 2 + maxTextureSize  || height > 2 + maxTextureSize ) ) {
            throw new GLException("Size "+width+"x"+height+" exceeds on of the maximum texture size "+maxTextureSize+": \n\t"+this);
        }
        if( width > maxRenderbufferSize || height > maxRenderbufferSize  ) {
            throw new GLException("Size "+width+"x"+height+" exceeds on of the maxima renderbuffer size "+maxRenderbufferSize+": \n\t"+this);
        }

        resetSamplingSink(gl);

        // generate fbo ..
        gl.glGenFramebuffers(1, val, 0);
        fbName = val[0];
        if(0 == fbName) {
            throw new GLException("null framebuffer");
        }

        // bind fbo ..
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbName);
        checkNoError(gl, gl.glGetError(), "FBObject Init.bindFB");  // throws GLException if error
        if(!gl.glIsFramebuffer(fbName)) {
            checkNoError(gl, GL.GL_INVALID_VALUE, "FBObject Init.isFB"); // throws GLException
        }
        bound = true;
        samplingSinkDirty = true;
        initialized = true;

        vStatus = GL.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT; // always incomplete w/o attachments!
        if(DEBUG) {
            System.err.println("FBObject.init() END: "+this);
            Thread.dumpStack();
        }
    }

    /**
     * Initializes or resets this FBO's instance.
     * <p>
     * In case the new parameters are compatible with the current ones
     * no action will be performed. Otherwise all attachments will be recreated
     * to match the new given parameters.
     * </p>
     * <p>
     * Incompatibility and hence recreation is forced if
     * the size or sample count doesn't match for subsequent calls.
     * </p>
     *
     * <p>Leaves the FBO bound state untouched</p>
     *
     * @param gl the current GL context
     * @param newWidth
     * @param newHeight
     * @throws GLException in case of an error
     */
    public final void reset(final GL gl, final int newWidth, final int newHeight) {
        reset(gl, newWidth, newHeight, 0, false);
    }

    /**
     * Initializes or resets this FBO's instance.
     * <p>
     * In case the new parameters are compatible with the current ones
     * no action will be performed. Otherwise all attachments will be recreated
     * to match the new given parameters.
     * </p>
     * <p>
     * Currently incompatibility and hence recreation of the attachments will be performed
     * if the size or sample count doesn't match for subsequent calls.
     * </p>
     *
     * <p>Leaves the FBO bound state untouched</p>
     *
     * @param gl the current GL context
     * @param newWidth the new width, it's minimum is capped to 1
     * @param newHeight the new height, it's minimum is capped to 1
     * @param newSamples if > 0, MSAA will be used, otherwise no multisampling. Will be capped to {@link #getMaxSamples()}.
     * @param resetSamplingSink <code>true</code> calls {@link #resetSamplingSink(GL)} immediatly.
     *                          <code>false</code> postpones resetting the sampling sink until {@link #use(GL, TextureAttachment)} or {@link #syncSamplingSink(GL)},
     *                          allowing to use the samples sink's FBO and texture until then. The latter is useful to benefit
     *                          from implicit double buffering while resetting the sink just before it's being used, eg. at swap-buffer.
     *
     * @throws GLException in case of an error, i.e. size too big, etc ..
     */
    public final void reset(final GL gl, int newWidth, int newHeight, int newSamples, final boolean resetSamplingSink) {
        if( !initialized ) {
            init(gl, newWidth, newHeight, newSamples);
            return;
        }

        newSamples = newSamples <= maxSamples ? newSamples : maxSamples; // clamp

        if( newWidth !=  width || newHeight !=  height || newSamples != samples ) {
            if( 0 >= newWidth )  { newWidth = 1; }
            if( 0 >= newHeight ) { newHeight = 1; }
            if( textureAttachmentCount > 0 && ( newWidth > 2 + maxTextureSize  || newHeight > 2 + maxTextureSize ) ) {
                throw new GLException("Size "+newWidth+"x"+newHeight+" exceeds on of the maximum texture size "+maxTextureSize+": \n\t"+this);
            }
            if( newWidth > maxRenderbufferSize || newHeight > maxRenderbufferSize  ) {
                throw new GLException("Size "+newWidth+"x"+newHeight+" exceeds on of the maxima renderbuffer size "+maxRenderbufferSize+": \n\t"+this);
            }

            if(DEBUG) {
                System.err.println("FBObject.reset - START - "+width+"x"+height+", "+samples+" -> "+newWidth+"x"+newHeight+", "+newSamples+"; "+this);
            }

            final boolean wasBound = isBound();

            final int sampleCountChange;
            if( 0 < samples && 0 < newSamples || 0 == samples && 0 == newSamples ) {
                sampleCountChange =  0; // keep MSAA settings
            } else if( 0 == samples && 0 < newSamples ) {
                sampleCountChange =  1; // add MSAA
            } else if( 0 < samples && 0 == newSamples ) {
                sampleCountChange = -1; // remove MSAA
            } else {
                throw new IllegalArgumentException("Error in sampleCount change: "+samples+" -> "+newSamples);
            }
            width = newWidth;
            height = newHeight;
            samples = newSamples;

            if(0 < samples && null == samplingSink ) {
                // needs valid samplingSink for detach*() -> bind()
                samplingSink = new FBObject();
                samplingSink.init(gl, width, height, 0);
            }
            detachAllImpl(gl, true, true, sampleCountChange);
            if(resetSamplingSink) {
                resetSamplingSink(gl);
            }

            samplingSinkDirty = true;

            if(!wasBound) {
                unbind(gl);
            }

            if(DEBUG) {
                System.err.println("FBObject.reset - END - "+this);
            }
        }
    }

    private void validateAttachmentSize(final Attachment a) {
        final int aWidth = a.getWidth();
        final int aHeight = a.getHeight();

        if( a instanceof TextureAttachment && ( aWidth > 2 + maxTextureSize  || aHeight > 2 + maxTextureSize ) ) {
            throw new GLException("Size "+aWidth+"x"+aHeight+" of "+a+" exceeds on of the maximum texture size "+maxTextureSize+": \n\t"+this);
        }
        if( aWidth > maxRenderbufferSize || aHeight > maxRenderbufferSize  ) {
            throw new GLException("Size "+aWidth+"x"+aHeight+" of "+a+" exceeds on of the maxima renderbuffer size "+maxRenderbufferSize+": \n\t"+this);
        }
    }

    /**
     * Writes the internal format of the attachments to the given GLCapabilities object.
     * @param caps the destination for format bits
     */
    public final void formatToGLCapabilities(final GLCapabilities caps) {
        caps.setSampleBuffers(samples > 0);
        caps.setNumSamples(samples);
        caps.setDepthBits(0);
        caps.setStencilBits(0);

        final Colorbuffer cb = samples > 0 ? getSamplingSink() : getColorbuffer(0);
        if(null != cb) {
            cb.formatToGLCapabilities(caps, rgba8Avail);
        }
        if(null != depth) {
            depth.formatToGLCapabilities(caps, rgba8Avail);
        }
        if(null != stencil && stencil != depth) {
            stencil.formatToGLCapabilities(caps, rgba8Avail);
        }
    }

    /**
     * Note that the status may reflect an incomplete state during transition of attachments.
     * @return The FB status. {@link GL.GL_FRAMEBUFFER_COMPLETE} if ok, otherwise return GL FBO error state or -1
     * @see #validateStatus()
     */
    public final int getStatus() {
        return vStatus;
    }

    /** return the {@link #getStatus()} as a string. */
    public final String getStatusString() {
        return getStatusString(vStatus);
    }

    public static final String getStatusString(final int fbStatus) {
        switch(fbStatus) {
            case -1:
                return "NOT A FBO";

            case GL.GL_FRAMEBUFFER_COMPLETE:
                return "OK";

            case GL.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT:
                return("FBO incomplete attachment\n");
            case GL.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT:
                return("FBO missing attachment");
            case GL.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS:
                return("FBO attached images must have same dimensions");
            case GL.GL_FRAMEBUFFER_INCOMPLETE_FORMATS:
                 return("FBO attached images must have same format");
            case GL2GL3.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER:
                return("FBO missing draw buffer");
            case GL2GL3.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER:
                return("FBO missing read buffer");
            case GL2ES3.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE:
                return("FBO missing multisample buffer");
            case GL3.GL_FRAMEBUFFER_INCOMPLETE_LAYER_TARGETS:
                return("FBO missing layer targets");

            case GL.GL_FRAMEBUFFER_UNSUPPORTED:
                return("Unsupported FBO format");
            case GL2ES3.GL_FRAMEBUFFER_UNDEFINED:
                 return("FBO undefined");

            case 0:
                return("FBO implementation fault");
            default:
                return("FBO incomplete, implementation ERROR "+toHexString(fbStatus));
        }
    }

    /**
     * The status may even be valid if incomplete during transition of attachments.
     * @see #getStatus()
     */
    public final boolean isStatusValid() {
        switch(vStatus) {
            case GL.GL_FRAMEBUFFER_COMPLETE:
                return true;

            case GL.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT:
            case GL.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT:
            case GL.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS:
            case GL.GL_FRAMEBUFFER_INCOMPLETE_FORMATS:
            case GL2GL3.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER:
            case GL2GL3.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER:
            case GL2ES3.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE:
            case GL3.GL_FRAMEBUFFER_INCOMPLETE_LAYER_TARGETS:
                if(0 == colorbufferCount || null == depth) {
                    // we are in transition
                    return true;
                }

            case GL.GL_FRAMEBUFFER_UNSUPPORTED:
            case GL2ES3.GL_FRAMEBUFFER_UNDEFINED:

            case 0:
            default:
                if(DEBUG) {
                    System.err.println("Framebuffer " + fbName + " is incomplete, status = " + toHexString(vStatus) +
                            " : " + getStatusString(vStatus));
                }
                return false;
        }
    }

    private static int checkPreGLError(final GL gl) {
        final int glerr = gl.glGetError();
        if(DEBUG && GL.GL_NO_ERROR != glerr) {
            System.err.println("Pre-existing GL error: "+toHexString(glerr));
            Thread.dumpStack();
        }
        return glerr;
    }

    private final boolean checkNoError(final GL gl, final int err, final String exceptionMessage) throws GLException {
        if(GL.GL_NO_ERROR != err) {
            if(null != gl) {
                destroy(gl);
            }
            if(null != exceptionMessage) {
                throw new GLException(exceptionMessage+" GL Error "+toHexString(err)+" of "+this.toString());
            }
            return false;
        }
        return true;
    }

    private final void checkInitialized() throws GLException {
        if(!initialized) {
            throw new GLException("FBO not initialized, call init(GL) first.");
        }
    }

    /**
     * Attaches a {@link Colorbuffer}, i.e. {@link TextureAttachment}, to this FBO's instance at the given attachment point,
     * selecting the texture data type and format automatically.
     *
     * <p>Using default min/mag filter {@link GL#GL_NEAREST} and default wrapS/wrapT {@link GL#GL_CLAMP_TO_EDGE}.</p>
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl the current GL context
     * @param attachmentPoint the color attachment point ranging from [0..{@link #getMaxColorAttachments()}-1]
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @return TextureAttachment instance describing the new attached texture colorbuffer if bound and configured successfully, otherwise GLException is thrown
     * @throws GLException in case the texture colorbuffer couldn't be allocated or MSAA has been chosen
     * @see #createColorTextureAttachment(GLProfile, boolean, int, int)
     */
    public final TextureAttachment attachTexture2D(final GL gl, final int attachmentPoint, final boolean alpha) throws GLException {
        return attachColorbuffer(gl, attachmentPoint,
                                 createColorTextureAttachment(gl, alpha, width, height)).getTextureAttachment();
    }

    /**
     * Attaches a {@link Colorbuffer}, i.e. {@link TextureAttachment}, to this FBO's instance at the given attachment point,
     * selecting the texture data type and format automatically.
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl the current GL context
     * @param attachmentPoint the color attachment point ranging from [0..{@link #getMaxColorAttachments()}-1]
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @param magFilter if > 0 value for {@link GL#GL_TEXTURE_MAG_FILTER}
     * @param minFilter if > 0 value for {@link GL#GL_TEXTURE_MIN_FILTER}
     * @param wrapS if > 0 value for {@link GL#GL_TEXTURE_WRAP_S}
     * @param wrapT if > 0 value for {@link GL#GL_TEXTURE_WRAP_T}
     * @return TextureAttachment instance describing the new attached texture colorbuffer if bound and configured successfully, otherwise GLException is thrown
     * @throws GLException in case the texture colorbuffer couldn't be allocated or MSAA has been chosen
     * @see #createColorTextureAttachment(GLProfile, boolean, int, int, int, int, int, int)
     */
    public final TextureAttachment attachTexture2D(final GL gl, final int attachmentPoint, final boolean alpha, final int magFilter, final int minFilter, final int wrapS, final int wrapT) throws GLException {
        return attachColorbuffer(gl, attachmentPoint,
                                 createColorTextureAttachment(gl, alpha, width, height, magFilter, minFilter, wrapS, wrapT)).getTextureAttachment();
    }

    /**
     * Attaches a {@link Colorbuffer}, i.e. {@link TextureAttachment}, to this FBO's instance at the given attachment point.
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl the current GL context
     * @param attachmentPoint the color attachment point ranging from [0..{@link #getMaxColorAttachments()}-1]
     * @param internalFormat internalFormat parameter to {@link GL#glTexImage2D(int, int, int, int, int, int, int, int, long)}
     * @param dataFormat format parameter to {@link GL#glTexImage2D(int, int, int, int, int, int, int, int, long)}
     * @param dataType type parameter to {@link GL#glTexImage2D(int, int, int, int, int, int, int, int, long)}
     * @param magFilter if > 0 value for {@link GL#GL_TEXTURE_MAG_FILTER}
     * @param minFilter if > 0 value for {@link GL#GL_TEXTURE_MIN_FILTER}
     * @param wrapS if > 0 value for {@link GL#GL_TEXTURE_WRAP_S}
     * @param wrapT if > 0 value for {@link GL#GL_TEXTURE_WRAP_T}
     * @return TextureAttachment instance describing the new attached texture colorbuffer if bound and configured successfully, otherwise GLException is thrown
     * @throws GLException in case the texture colorbuffer couldn't be allocated or MSAA has been chosen
     * @see #createColorTextureAttachment(int, int, int, int, int, int, int, int, int)
     */
    public final TextureAttachment attachTexture2D(final GL gl, final int attachmentPoint,
                                                   final int internalFormat, final int dataFormat, final int dataType,
                                                   final int magFilter, final int minFilter, final int wrapS, final int wrapT) throws GLException {
        return attachColorbuffer(gl, attachmentPoint,
                                 createColorTextureAttachment(internalFormat, width, height, dataFormat, dataType, magFilter, minFilter, wrapS, wrapT)).getTextureAttachment();
    }

    /**
     * Creates a {@link ColorAttachment}, selecting the format automatically.
     * <p>
     * For GLES3, sampling-sink {@link Colorbuffer} format <b>must be equal</b> w/ the sampling-source {@link Colorbuffer}.
     * Implementation aligns w/ {@link #createColorTextureAttachment(GLProfile, boolean, int, int, int, int, int, int)}
     * and is enforced via {@link #sampleSinkFormatMismatch(GL)}.
     * </p>
     *
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @return uninitialized ColorAttachment instance describing the new attached colorbuffer
     */
    public final ColorAttachment createColorAttachment(final boolean alpha) {
        final int internalFormat;

        if( rgba8Avail ) {
            internalFormat = alpha ? GL.GL_RGBA8 : GL.GL_RGB8 ;
        } else {
            internalFormat = alpha ? GL.GL_RGBA4 : GL.GL_RGB565;
        }
        return createColorAttachment(internalFormat, samples, width, height);
    }

    /**
     * Creates a {@link ColorAttachment}, selecting the format automatically.
     * <p>
     * For GLES3, sampling-sink {@link Colorbuffer} format <b>must be equal</b> w/ the sampling-source {@link Colorbuffer}.
     * Implementation aligns w/ {@link #createColorTextureAttachment(GLProfile, boolean, int, int, int, int, int, int)}
     * and is enforced via {@link #sampleSinkFormatMismatch(GL)}.
     * </p>
     *
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @return uninitialized ColorAttachment instance describing the new attached colorbuffer
     */
    public static final ColorAttachment createColorAttachment(final int internalFormat, final int samples, final int width, final int height) {
        return new ColorAttachment(internalFormat, samples, width, height, 0 /* name not yet determined */);
    }

    public static final RenderAttachment createRenderAttachment(final Type type, final int internalFormat, final int samples, final int width, final int height) {
        return new RenderAttachment(type, internalFormat, samples, width, height, 0 /* name not yet determined */);
    }

    /**
     * Attaches a {@link Colorbuffer}, i.e. {@link ColorAttachment}, to this FBO's instance at the given attachment point,
     * selecting the format automatically.
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl the current GL context
     * @param attachmentPoint the color attachment point ranging from [0..{@link #getMaxColorAttachments()}-1]
     * @param alpha set to <code>true</code> if you request alpha channel, otherwise <code>false</code>;
     * @return ColorAttachment instance describing the new attached colorbuffer if bound and configured successfully, otherwise GLException is thrown
     * @throws GLException in case the colorbuffer couldn't be allocated
     * @see #createColorAttachment(boolean)
     */
    public final ColorAttachment attachColorbuffer(final GL gl, final int attachmentPoint, final boolean alpha) throws GLException {
        return attachColorbuffer(gl, attachmentPoint, createColorAttachment(alpha)).getColorAttachment();
    }

    /**
     * Attaches a {@link Colorbuffer}, i.e. {@link ColorAttachment}, to this FBO's instance at the given attachment point.
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl the current GL context
     * @param attachmentPoint the color attachment point ranging from [0..{@link #getMaxColorAttachments()}-1]
     * @param internalFormat usually {@link GL#GL_RGBA4}, {@link GL#GL_RGB5_A1}, {@link GL#GL_RGB565}, {@link GL#GL_RGB8} or {@link GL#GL_RGBA8}
     * @return ColorAttachment instance describing the new attached colorbuffer if bound and configured successfully, otherwise GLException is thrown
     * @throws GLException in case the colorbuffer couldn't be allocated
     * @throws IllegalArgumentException if <code>internalFormat</code> doesn't reflect a colorbuffer
     */
    public final ColorAttachment attachColorbuffer(final GL gl, final int attachmentPoint, final int internalFormat) throws GLException, IllegalArgumentException {
        final Attachment.Type atype = Attachment.Type.determine(internalFormat);
        if( Attachment.Type.COLOR != atype ) {
            throw new IllegalArgumentException("colorformat invalid: "+toHexString(internalFormat)+", "+this);
        }

        return attachColorbuffer(gl, attachmentPoint, createColorAttachment(internalFormat, samples, width, height)).getColorAttachment();
    }

    /**
     * Attaches a {@link Colorbuffer}, i.e. {@link ColorAttachment} or {@link TextureAttachment},
     * to this FBO's instance at the given attachment point.
     *
     * <p>
     * If {@link Colorbuffer} is a {@link TextureAttachment} and is uninitialized, i.e. it's texture name is <code>zero</code>,
     * a new texture name is generated and setup w/ the texture parameter.<br/>
     * Otherwise, i.e. texture name is not <code>zero</code>, the passed TextureAttachment <code>texA</code> is
     * considered complete and assumed matching this FBO requirement. A GL error may occur is the latter is untrue.
     * </p>
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl
     * @param attachmentPoint the color attachment point ranging from [0..{@link #getMaxColorAttachments()}-1]
     * @param colbuf the to be attached {@link Colorbuffer}
     * @return newly attached {@link Colorbuffer} instance if bound and configured successfully, otherwise GLException is thrown
     * @throws GLException in case the colorbuffer couldn't be allocated or MSAA has been chosen in case of a {@link TextureAttachment}
     */
    public final Colorbuffer attachColorbuffer(final GL gl, final int attachmentPoint, final Colorbuffer colbuf) throws GLException {
        bind(gl);
        return attachColorbufferImpl(gl, attachmentPoint, colbuf);
    }

    private final Colorbuffer attachColorbufferImpl(final GL gl, final int attachmentPoint, final Colorbuffer colbuf) throws GLException {
        validateAddColorAttachment(attachmentPoint, colbuf);

        validateAttachmentSize((Attachment)colbuf);
        final boolean initializedColorbuf = colbuf.initialize(gl);
        addColorAttachment(attachmentPoint, colbuf);

        if( colbuf.isTextureAttachment() ) {
            final TextureAttachment texA = colbuf.getTextureAttachment();
            if( samples > 0 ) {
                removeColorAttachment(attachmentPoint, texA);
                if(initializedColorbuf) {
                    texA.free(gl);
                }
                throw new GLException("Texture2D not supported w/ MSAA. If you have enabled MSAA with exisiting texture attachments, you may want to detach them via detachAllTexturebuffer(gl).");
            }

            // Set up the color buffer for use as a renderable texture:
            gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,
                                      GL.GL_COLOR_ATTACHMENT0 + attachmentPoint,
                                      GL.GL_TEXTURE_2D, texA.getName(), 0);

            if(!ignoreStatus) {
                updateStatus(gl);
                if(!isStatusValid()) {
                    detachColorbuffer(gl, attachmentPoint, true);
                    throw new GLException("attachTexture2D "+texA+" at "+attachmentPoint+" failed "+getStatusString()+", "+this);
                }
            }
        } else {
            final ColorAttachment colA = colbuf.getColorAttachment();

            // Attach the color buffer
            gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER,
                                         GL.GL_COLOR_ATTACHMENT0 + attachmentPoint,
                                         GL.GL_RENDERBUFFER, colA.getName());

            if(!ignoreStatus) {
                updateStatus(gl);
                if(!isStatusValid()) {
                    detachColorbuffer(gl, attachmentPoint, true);
                    throw new GLException("attachColorbuffer "+colA+" at "+attachmentPoint+" failed "+getStatusString()+", "+this);
                }
            }
        }
        if(DEBUG) {
            System.err.println("FBObject.attachColorbuffer.X: [attachmentPoint "+attachmentPoint+", colbuf "+colbuf+"]: "+this);
        }
        return colbuf;
    }

    /**
     * Attaches one depth, stencil or packed-depth-stencil buffer to this FBO's instance,
     * selecting the internalFormat automatically.
     * <p>
     * Stencil and depth buffer can be attached only once.
     * </p>
     * <p>
     * In case the desired type or bit-number is not supported, the next available one is chosen.
     * </p>
     * <p>
     * Use {@link #getDepthAttachment()} and/or {@link #getStencilAttachment()} to retrieve details
     * about the attached buffer. The details cannot be returned, since it's possible 2 buffers
     * are being created, depth and stencil.
     * </p>
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl
     * @param atype either {@link Type#DEPTH}, {@link Type#STENCIL} or {@link Type#DEPTH_STENCIL}
     * @param reqBits desired bits for depth or -1 for default (24 bits)
     * @throws GLException in case the renderbuffer couldn't be allocated or one is already attached.
     * @throws IllegalArgumentException
     * @see #getDepthAttachment()
     * @see #getStencilAttachment()
     */
    public final void attachRenderbuffer(final GL gl, final Attachment.Type atype, int reqBits) throws GLException, IllegalArgumentException {
        if( 0 > reqBits ) {
            reqBits = 24;
        }
        final int internalFormat;
        int internalStencilFormat = -1;

        switch ( atype ) {
            case DEPTH:
                if( 32 <= reqBits && depth32Avail ) {
                    internalFormat = GL.GL_DEPTH_COMPONENT32;
                } else if( 24 <= reqBits && depth24Avail ) {
                    internalFormat = GL.GL_DEPTH_COMPONENT24;
                } else {
                    internalFormat = GL.GL_DEPTH_COMPONENT16;
                }
                break;

            case STENCIL:
                if( 16 <= reqBits && stencil16Avail ) {
                    internalFormat = GL2GL3.GL_STENCIL_INDEX16;
                } else if( 8 <= reqBits && stencil08Avail ) {
                    internalFormat = GL.GL_STENCIL_INDEX8;
                } else if( 4 <= reqBits && stencil04Avail ) {
                    internalFormat = GL.GL_STENCIL_INDEX4;
                } else if( 1 <= reqBits && stencil01Avail ) {
                    internalFormat = GL.GL_STENCIL_INDEX1;
                } else {
                    throw new GLException("stencil buffer n/a");
                }
                break;

            case DEPTH_STENCIL:
                if( packedDepthStencilAvail ) {
                    internalFormat = GL.GL_DEPTH24_STENCIL8;
                } else {
                    if( 24 <= reqBits && depth24Avail ) {
                        internalFormat = GL.GL_DEPTH_COMPONENT24;
                    } else {
                        internalFormat = GL.GL_DEPTH_COMPONENT16;
                    }
                    if( stencil08Avail ) {
                        internalStencilFormat = GL.GL_STENCIL_INDEX8;
                    } else if( stencil04Avail ) {
                        internalStencilFormat = GL.GL_STENCIL_INDEX4;
                    } else if( stencil01Avail ) {
                        internalStencilFormat = GL.GL_STENCIL_INDEX1;
                    } else {
                        throw new GLException("stencil buffer n/a");
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("only depth/stencil types allowed, was "+atype+", "+this);
        }

        attachRenderbufferImpl(gl, atype, internalFormat);

        if(0<=internalStencilFormat) {
            attachRenderbufferImpl(gl, Attachment.Type.STENCIL, internalStencilFormat);
        }
    }

    /**
     * Attaches one depth, stencil or packed-depth-stencil buffer to this FBO's instance,
     * depending on the <code>internalFormat</code>.
     * <p>
     * Stencil and depth buffer can be attached only once.
     * </p>
     * <p>
     * Use {@link #getDepthAttachment()} and/or {@link #getStencilAttachment()} to retrieve details
     * about the attached buffer. The details cannot be returned, since it's possible 2 buffers
     * are being created, depth and stencil.
     * </p>
     *
     * <p>Leaves the FBO bound.</p>
     *
     * @param gl the current GL context
     * @param internalFormat {@link GL#GL_DEPTH_COMPONENT16}, {@link GL#GL_DEPTH_COMPONENT24}, {@link GL#GL_DEPTH_COMPONENT32},
     *                       {@link GL#GL_STENCIL_INDEX1}, {@link GL#GL_STENCIL_INDEX4}, {@link GL#GL_STENCIL_INDEX8}
     *                       or {@link GL#GL_DEPTH24_STENCIL8}
     * @throws GLException in case the renderbuffer couldn't be allocated or one is already attached.
     * @throws IllegalArgumentException
     * @see #getDepthAttachment()
     * @see #getStencilAttachment()
     */
    public final void attachRenderbuffer(final GL gl, final int internalFormat) throws GLException, IllegalArgumentException {
        final Attachment.Type atype = Attachment.Type.determine(internalFormat);
        if( Attachment.Type.DEPTH != atype && Attachment.Type.STENCIL != atype && Attachment.Type.DEPTH_STENCIL != atype ) {
            throw new IllegalArgumentException("renderformat invalid: "+toHexString(internalFormat)+", "+this);
        }
        attachRenderbufferImpl(gl, atype, internalFormat);
    }

    protected final void attachRenderbufferImpl(final GL gl, final Attachment.Type atype, final int internalFormat) throws GLException {
        if( null != depth && ( Attachment.Type.DEPTH == atype || Attachment.Type.DEPTH_STENCIL == atype ) ) {
            throw new GLException("FBO depth buffer already attached (rb "+depth+"), type is "+atype+", "+toHexString(internalFormat)+", "+this);
        }
        if( null != stencil && ( Attachment.Type.STENCIL== atype || Attachment.Type.DEPTH_STENCIL == atype ) ) {
            throw new GLException("FBO stencil buffer already attached (rb "+stencil+"), type is "+atype+", "+toHexString(internalFormat)+", "+this);
        }
        bind(gl);

        attachRenderbufferImpl2(gl, atype, internalFormat);
    }

    private final void attachRenderbufferImpl2(final GL gl, final Attachment.Type atype, final int internalFormat) throws GLException {
        if( Attachment.Type.DEPTH == atype ) {
            if(null == depth) {
                depth = createRenderAttachment(Type.DEPTH, internalFormat, samples, width, height);
            } else {
                depth.setSize(width, height);
                depth.setSamples(samples);
            }
            validateAttachmentSize(depth);
            depth.initialize(gl);
        } else if( Attachment.Type.STENCIL == atype ) {
            if(null == stencil) {
                stencil = createRenderAttachment(Type.STENCIL, internalFormat, samples, width, height);
            } else {
                stencil.setSize(width, height);
                stencil.setSamples(samples);
            }
            validateAttachmentSize(stencil);
            stencil.initialize(gl);
        } else if( Attachment.Type.DEPTH_STENCIL == atype ) {
            if(null == depth) {
                if(null != stencil) {
                    throw new InternalError("XXX: DEPTH_STENCIL, depth was null, stencil not: "+this.toString());
                }
                depth = createRenderAttachment(Type.DEPTH_STENCIL, internalFormat, samples, width, height);
            } else {
                depth.setSize(width, height);
                depth.setSamples(samples);
            }
            validateAttachmentSize(depth);
            depth.initialize(gl);
            // DEPTH_STENCIL shares buffer w/ depth and stencil
            stencil = depth;
        }

        // Attach the buffer
        if( Attachment.Type.DEPTH == atype ) {
            gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depth.getName());
        } else if( Attachment.Type.STENCIL == atype ) {
            gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, stencil.getName());
        } else if( Attachment.Type.DEPTH_STENCIL == atype ) {
            gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depth.getName());
            gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, stencil.getName());
        }

        if(!ignoreStatus) {
            updateStatus(gl);
            if( !isStatusValid() ) {
                detachRenderbuffer(gl, atype, true);
                throw new GLException("renderbuffer [attachmentType "+atype+", iformat "+toHexString(internalFormat)+"] failed: "+this.getStatusString()+": "+this.toString());
            }
        }

        if(DEBUG) {
            System.err.println("FBObject.attachRenderbuffer.X: [attachmentType "+atype+", iformat "+toHexString(internalFormat)+"]: "+this);
        }
    }

    /**
     * Detaches a {@link Colorbuffer}, i.e. {@link ColorAttachment} or {@link TextureAttachment}.
     * <p>Leaves the FBO bound!</p>
     *
     * @param gl
     * @param attachmentPoint
     * @param dispose true if the Colorbuffer shall be disposed
     * @return the detached Colorbuffer
     * @throws IllegalArgumentException
     */
    public final Colorbuffer detachColorbuffer(final GL gl, final int attachmentPoint, final boolean dispose) throws IllegalArgumentException {
        bind(gl);

        final Colorbuffer res = detachColorbufferImpl(gl, attachmentPoint, dispose ? DetachAction.DISPOSE : DetachAction.NONE, 0);
        if(null == res) {
            throw new IllegalArgumentException("ColorAttachment at "+attachmentPoint+", not attached, "+this);
        }
        if(DEBUG) {
            System.err.println("FBObject.detachColorbuffer.X: [attachmentPoint "+attachmentPoint+", dispose "+dispose+"]: "+res+", "+this);
        }
        return res;
    }

    private final Colorbuffer detachColorbufferImpl(final GL gl, final int attachmentPoint, final DetachAction detachAction, final int sampleCountChange) {
        Colorbuffer colbuf = colorbufferAttachments[attachmentPoint]; // shortcut, don't validate here

        if(null == colbuf) {
            return null;
        }

        removeColorAttachment(attachmentPoint, colbuf);

        if( colbuf.isTextureAttachment() ) {
            final TextureAttachment texA = colbuf.getTextureAttachment();
            if( 0 != texA.getName() ) {
                gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,
                              GL.GL_COLOR_ATTACHMENT0 + attachmentPoint,
                              GL.GL_TEXTURE_2D, 0, 0);
                gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
                switch(detachAction) {
                    case DISPOSE:
                    case RECREATE:
                        texA.free(gl);
                        break;
                    default:
                }
            }
            if(DetachAction.RECREATE == detachAction) {
                if( 0 < sampleCountChange ) {
                    // switch to MSAA: TextureAttachment -> ColorAttachment
                    colbuf = createColorAttachment(hasAlpha(texA.format));
                } else {
                    // keep MSAA settings
                    texA.setSize(width, height);
                }
                attachColorbufferImpl(gl, attachmentPoint, colbuf);
            }
        } else {
            final ColorAttachment colA = colbuf.getColorAttachment();
            if( 0 != colA.getName() ) {
                gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER,
                                             GL.GL_COLOR_ATTACHMENT0+attachmentPoint,
                                             GL.GL_RENDERBUFFER, 0);
                switch(detachAction) {
                    case DISPOSE:
                    case RECREATE:
                        colA.free(gl);
                        break;
                    default:
                }
            }
            if(DetachAction.RECREATE == detachAction) {
                if( 0 <= sampleCountChange || null == samplingColorSink ) {
                    // keep ColorAttachment,
                    // including 'switch to non-MSAA' if no samplingColorSink is available
                    // to determine whether a TextureAttachment or ColorAttachment is desired!
                    colA.setSize(width, height);
                    colA.setSamples(samples);
                } else {
                    // switch to non MSAA
                    if( samplingColorSink.isTextureAttachment() ) {
                        final TextureAttachment samplingTextureSink = samplingColorSink.getTextureAttachment();
                        colbuf = createColorTextureAttachment(samplingTextureSink.format, width, height,
                                                              samplingTextureSink.dataFormat, samplingTextureSink.dataType,
                                                              samplingTextureSink.magFilter, samplingTextureSink.minFilter,
                                                              samplingTextureSink.wrapS, samplingTextureSink.wrapT);
                    } else {
                        colbuf = createColorAttachment(samplingColorSink.getFormat(), 0, width, height);
                    }
                }
                attachColorbuffer(gl, attachmentPoint, colbuf);
            }
        }
        return colbuf;
    }

    private final void freeAllColorbufferImpl(final GL gl) {
        for(int i=0; i<maxColorAttachments; i++) {
            final Colorbuffer colbuf = colorbufferAttachments[i]; // shortcut, don't validate here

            if(null == colbuf) {
                return;
            }

            if( colbuf.isTextureAttachment() ) {
                final TextureAttachment texA = colbuf.getTextureAttachment();
                if( 0 != texA.getName() ) {
                    gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,
                                  GL.GL_COLOR_ATTACHMENT0 + i,
                                  GL.GL_TEXTURE_2D, 0, 0);
                    gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
                }
                texA.free(gl);
            } else {
                final ColorAttachment colA = colbuf.getColorAttachment();
                if( 0 != colA.getName() ) {
                    gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER,
                                                 GL.GL_COLOR_ATTACHMENT0 + i,
                                                 GL.GL_RENDERBUFFER, 0);
                }
                colA.free(gl);
            }
        }
    }

    /**
     *
     * @param gl
     * @param dispose true if the Colorbuffer shall be disposed
     * @param reqAType {@link Type#DEPTH}, {@link Type#DEPTH} or {@link Type#DEPTH_STENCIL}
     */
    public final void detachRenderbuffer(final GL gl, final Attachment.Type atype, final boolean dispose) throws IllegalArgumentException {
        bind(gl);
        detachRenderbufferImpl(gl, atype, dispose ? DetachAction.DISPOSE : DetachAction.NONE);
        if(DEBUG) {
            System.err.println("FBObject.detachRenderbuffer.X: [attachmentType "+atype+", dispose "+dispose+"]: "+this);
        }
    }

    public final boolean isDepthStencilPackedFormat() {
        final boolean res = null != depth && null != stencil &&
                            depth.format == stencil.format ;
        if(res) {
            if(depth.getName() != stencil.getName() ) {
                throw new InternalError("depth/stencil packed format not sharing: depth "+depth+", stencil "+stencil);
            }
            if(depth != stencil) {
                throw new InternalError("depth/stencil packed format not a shared reference: depth "+depth+", stencil "+stencil);
            }
        }
        return res;
    }

    private final void detachRenderbufferImpl(final GL gl, Attachment.Type atype, final DetachAction detachAction) throws IllegalArgumentException {
        switch ( atype ) {
            case DEPTH:
            case STENCIL:
            case DEPTH_STENCIL:
             break;
             default:
                 throw new IllegalArgumentException("only depth/stencil types allowed, was "+atype+", "+this);
        }
        if( null == depth && null == stencil ) {
            return ; // nop
        }
        final boolean packed = isDepthStencilPackedFormat();
        if( packed ) {
            // Note: DEPTH_STENCIL shares buffer w/ depth and stencil
            atype = Attachment.Type.DEPTH_STENCIL;
        }
        switch ( atype ) {
            case DEPTH:
                if( null != depth ) {
                    final int format = depth.format;
                    if( 0 != depth.getName() ) {
                        gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                        switch(detachAction) {
                            case DISPOSE:
                            case RECREATE:
                                depth.free(gl);
                                break;
                            default:
                        }
                    }
                    if(DetachAction.RECREATE == detachAction) {
                        attachRenderbufferImpl2(gl, atype, format);
                    } else {
                        depth = null;
                    }
                }
                break;
            case STENCIL:
                if( null != stencil ) {
                    final int format = stencil.format;
                    if(0 != stencil.getName()) {
                        gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                        switch(detachAction) {
                            case DISPOSE:
                            case RECREATE:
                                stencil.free(gl);
                                break;
                            default:
                        }
                    }
                    if(DetachAction.RECREATE == detachAction) {
                        attachRenderbufferImpl2(gl, atype, format);
                    } else {
                        stencil = null;
                    }
                }
                break;
            case DEPTH_STENCIL:
                if( null != depth ) {
                    final int format = depth.format;
                    if(0 != depth.getName()) {
                        gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                        if(packed) {
                            gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                        }
                        switch(detachAction) {
                            case DISPOSE:
                            case RECREATE:
                                depth.free(gl);
                                break;
                            default:
                        }
                    }
                    if(DetachAction.RECREATE == detachAction) {
                        attachRenderbufferImpl2(gl, packed ? Attachment.Type.DEPTH_STENCIL : Attachment.Type.DEPTH, format);
                    } else {
                        depth = null;
                        if(packed) {
                            stencil = null;
                        }
                    }
                }
                if( !packed && null != stencil ) {
                    final int format = stencil.format;
                    if(0 != stencil.getName()) {
                        gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                        switch(detachAction) {
                            case DISPOSE:
                            case RECREATE:
                                stencil.free(gl);
                                break;
                            default:
                        }
                    }
                    if(DetachAction.RECREATE == detachAction) {
                        attachRenderbufferImpl2(gl, Attachment.Type.STENCIL, format);
                    } else {
                        stencil = null;
                    }
                }
                break;
             default: // handled
        }
    }

    private final void freeAllRenderbufferImpl(final GL gl) throws IllegalArgumentException {
        // Note: DEPTH_STENCIL shares buffer w/ depth and stencil
        final boolean packed = isDepthStencilPackedFormat();
        if( null != depth ) {
            if(0 != depth.getName()) {
                gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                if(packed) {
                    gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                }
                depth.free(gl);
            }
        }
        if( !packed && null != stencil ) {
            if(0 != stencil.getName()) {
                gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_STENCIL_ATTACHMENT, GL.GL_RENDERBUFFER, 0);
                stencil.free(gl);
            }
        }
    }

    /**
     * Detaches all {@link ColorAttachment}s, {@link TextureAttachment}s and {@link RenderAttachment}s
     * and disposes them.
     * <p>Leaves the FBO bound, if initialized!</p>
     * <p>
     * An attached sampling sink texture will be detached as well, see {@link #getSamplingTextureSink()}.
     * </p>
     * @param gl the current GL context
     */
    public final void detachAll(final GL gl) {
        if(null != samplingSink) {
            samplingSink.detachAll(gl);
        }
        detachAllImpl(gl, true/* detachNonColorbuffer */, false /* recreate */, 0);
    }

    /**
     * Detaches all {@link ColorAttachment}s and {@link TextureAttachment}s
     * and disposes them.
     * <p>Leaves the FBO bound, if initialized!</p>
     * <p>
     * An attached sampling sink texture will be detached as well, see {@link #getSamplingTextureSink()}.
     * </p>
     * @param gl the current GL context
     */
    public final void detachAllColorbuffer(final GL gl) {
        if(null != samplingSink) {
            samplingSink.detachAllColorbuffer(gl);
        }
        detachAllImpl(gl, false/* detachNonColorbuffer */, false /* recreate */, 0);
    }

    /**
     * Detaches all {@link TextureAttachment}s and disposes them.
     * <p>Leaves the FBO bound, if initialized!</p>
     * <p>
     * An attached sampling sink texture will be detached as well, see {@link #getSamplingTextureSink()}.
     * </p>
     * @param gl the current GL context
     */
    public final void detachAllTexturebuffer(final GL gl) {
        if( !isInitialized() ) {
            return;
        }
        if(null != samplingSink) {
            samplingSink.detachAllTexturebuffer(gl);
        }
        bind(gl);
        for(int i=0; i<maxColorAttachments; i++) {
            if( colorbufferAttachments[i].isTextureAttachment() ) {
                detachColorbufferImpl(gl, i, DetachAction.DISPOSE, 0);
            }
        }
        if(DEBUG) {
            System.err.println("FBObject.detachAllTexturebuffer.X: "+this);
        }
    }

    public final void detachAllRenderbuffer(final GL gl) {
        if( !isInitialized() ) {
            return;
        }
        if(null != samplingSink) {
            samplingSink.detachAllRenderbuffer(gl);
        }
        bind(gl);
        detachRenderbufferImpl(gl, Attachment.Type.DEPTH_STENCIL, DetachAction.DISPOSE);
    }

    private final void detachAllImpl(final GL gl, final boolean detachNonColorbuffer, final boolean recreate, final int sampleCountChange) {
        if( !isInitialized() ) {
            return;
        }
        ignoreStatus = recreate; // ignore status on single calls only if recreate -> reset
        try {
            bind(gl);
            if(FBOResizeQuirk) {
                if(detachNonColorbuffer && recreate) {
                    // free all colorbuffer & renderbuffer 1st
                    freeAllColorbufferImpl(gl);
                    freeAllRenderbufferImpl(gl);
                }
            }
            for(int i=0; i<maxColorAttachments; i++) {
                detachColorbufferImpl(gl, i, recreate ? DetachAction.RECREATE : DetachAction.DISPOSE, sampleCountChange);
            }
            if( !recreate && colorbufferCount>0 ) {
                throw new InternalError("Non zero ColorAttachments "+this);
            }

            if(detachNonColorbuffer) {
                detachRenderbufferImpl(gl, Attachment.Type.DEPTH_STENCIL, recreate ? DetachAction.RECREATE : DetachAction.DISPOSE);
            }
            if(ignoreStatus) { // post validate
                /* if(true) {
                    throw new GLException("Simulating bug 617, reset FBO failure");
                } */
                updateStatus(gl);
                if(!isStatusValid()) {
                    throw new GLException("detachAllImpl failed "+getStatusString()+", "+this);
                }
            }
        } finally {
            ignoreStatus = false;
        }
        if(DEBUG) {
            System.err.println("FBObject.detachAll.X: [resetNonColorbuffer "+detachNonColorbuffer+", recreate "+recreate+"]: "+this);
        }
    }

    /**
     * @param gl the current GL context
     */
    public final void destroy(final GL gl) {
        if(!initialized) {
            return;
        }
        if(DEBUG) {
            System.err.println("FBObject.destroy.0: "+this);
            // Thread.dumpStack();
        }
        if( null != samplingSink && samplingSink.isInitialized() ) {
            samplingSink.destroy(gl);
        }

        detachAllImpl(gl, true /* detachNonColorbuffer */, false /* recreate */, 0);

        // cache FB names, preset exposed to zero,
        // braking ties w/ GL/GLContext link to getReadFramebuffer()/getWriteFramebuffer()
        final int fb_cache = fbName;
        fbName = 0;

        final int name[] = new int[1];
        if(0!=fb_cache) {
            name[0] = fb_cache;
            gl.glDeleteFramebuffers(1, name, 0);
        }
        initialized = false;
        bound = false;
        if(DEBUG) {
            System.err.println("FBObject.destroy.X: "+this);
        }
    }

    private final boolean sampleSinkSizeMismatch() {
        return samplingSink.getWidth() != width || samplingSink.getHeight() != height ;
    }
    private final boolean sampleColorsinkUninit() {
        return null != samplingColorSink && 0 == samplingColorSink.getName() ;
    }
    private final boolean sampleSinkDepthStencilMismatch() {
        final boolean depthMismatch   = null != depth &&
                                        ( null == samplingSink.depth ||
                                          depth.format != samplingSink.depth.format );

        final boolean stencilMismatch = null != stencil &&
                                        ( null == samplingSink.stencil ||
                                          stencil.format != samplingSink.stencil.format );

        return depthMismatch || stencilMismatch;
    }
    /**
     * For GLES3, sampling-sink {@link Colorbuffer} format <b>must be equal</b> w/ the sampling-source {@link Colorbuffer}.
     * Implementation aligns w/ {@link #createColorTextureAttachment(GLProfile, boolean, int, int, int, int, int, int)}
     * and {@link #createColorAttachment(boolean)}.
     */
    private final boolean sampleSinkFormatMismatch(final GL gl) {
        if( null != samplingColorSink && getColorbufferCount() > 0 && gl.isGL2ES3() ) {
            final Attachment ca = (Attachment)getColorbuffer(0); // should be at attachment-point 0
            return ( null != ca && ca.format != samplingColorSink.getFormat() ) ||
                   hasAlpha(samplingColorSink.getFormat()) != hasAttachmentUsingAlpha();
        }
        return false;
    }

    /**
     * Manually reset the MSAA sampling sink, if used.
     * <p>
     * If MSAA is being used and no sampling sink is attached via {@link #setSamplingSink(FBObject)}
     * a new sampling sink is being created.
     * </p>
     * <p>
     * Automatically called by {@link #reset(GL, int, int, int, boolean)}
     * and {@link #syncSamplingSink(GL)}.
     * </p>
     * <p>
     * It is recommended to call this method after initializing the FBO and attaching renderbuffer etc for the 1st time
     * if access to sampling sink resources is required.
     * </p>
     * @param gl the current GL context
     * @throws GLException in case of an error, i.e. size too big, etc ..
     */
    public final void resetSamplingSink(final GL gl) throws GLException {
        if(DEBUG) {
            System.err.println("FBObject.resetSamplingSink.0");
        }
        if( 0 == samples ) {
            // MSAA off
            if(null != samplingSink && samplingSink.initialized) {
                // cleanup
                samplingSink.detachAll(gl);
            }
            if(DEBUG) {
                System.err.println("FBObject.resetSamplingSink.X1: zero samples \n\tTHIS "+this);
            }
            return;
        }

        if( null == samplingSink ) {
            samplingSink = new FBObject();
        }

        if( !samplingSink.initialized ) {
            samplingSink.init(gl, width, height, 0);
        }

        if(DEBUG) {
            System.err.println("FBObject.resetSamplingSink.1: \n\tTHIS "+this+",\n\tSINK "+samplingSink);
        }
        boolean sampleSinkFormatMismatch = sampleSinkFormatMismatch(gl);
        boolean sampleSinkSizeMismatch = sampleSinkSizeMismatch();
        boolean sampleSinkDepthStencilMismatch = sampleSinkDepthStencilMismatch();
        boolean sampleColorsinkUninit = sampleColorsinkUninit();

        if(!sampleSinkFormatMismatch && !sampleSinkSizeMismatch && !sampleColorsinkUninit && !sampleSinkDepthStencilMismatch) {
            if(DEBUG) {
                System.err.println("FBObject.resetSamplingSink.X2: Matching: format "+!sampleSinkFormatMismatch+", size "+!sampleSinkSizeMismatch +", csUninit "+!sampleColorsinkUninit +", depthStencil "+!sampleSinkDepthStencilMismatch);
            }
            // all properties match ..
            return;
        }

        unbind(gl);

        if(DEBUG) {
            System.err.println("FBObject.resetSamplingSink.2: Mismatch. Matching: format "+!sampleSinkFormatMismatch+", size "+!sampleSinkSizeMismatch +", csUninit "+!sampleColorsinkUninit +", depthStencil "+!sampleSinkDepthStencilMismatch);
            Thread.dumpStack();
        }

        if( sampleSinkDepthStencilMismatch ) {
            samplingSink.detachAllRenderbuffer(gl);
        }

        final Colorbuffer preSamplingColorSink = samplingColorSink;
        final boolean samplingColorSinkShallBeTA = null == preSamplingColorSink || preSamplingColorSink.isTextureAttachment();

        if( sampleSinkFormatMismatch ) {
            samplingSink.detachAllColorbuffer(gl);
            samplingColorSink = null;
        } else if( sampleSinkSizeMismatch ) {
            samplingSink.reset(gl, width, height);
        }

        if(null == samplingColorSink) {
            final boolean hasAlpha = hasAttachmentUsingAlpha();
            if( samplingColorSinkShallBeTA ) {
                samplingColorSink = samplingSink.attachTexture2D(gl, 0, hasAlpha);
            } else {
                samplingColorSink = samplingSink.attachColorbuffer(gl, 0, hasAlpha);
            }
        } else if( 0 == samplingColorSink.getName() ) {
            // final boolean dispose = true;
            // detachColorbufferImpl(gl, 0, dispose ? DetachAction.DISPOSE : DetachAction.NONE);
            ((Attachment)samplingColorSink).setSize(width, height);
            samplingSink.attachColorbuffer(gl, 0, samplingColorSink);
        }

        if( sampleSinkDepthStencilMismatch ) {
            samplingSink.attachRenderbuffer(gl, depth.format);
            if( null != stencil && !isDepthStencilPackedFormat() ) {
                samplingSink.attachRenderbuffer(gl, stencil.format);
            }
        }

        sampleSinkFormatMismatch = sampleSinkFormatMismatch(gl);
        sampleSinkSizeMismatch = sampleSinkSizeMismatch();
        sampleSinkDepthStencilMismatch = sampleSinkDepthStencilMismatch();
        sampleColorsinkUninit = sampleColorsinkUninit();
        if(sampleSinkFormatMismatch || sampleSinkSizeMismatch || sampleColorsinkUninit || sampleSinkDepthStencilMismatch) {
            throw new InternalError("Samples sink mismatch after reset: \n\tTHIS "+this+",\n\t SINK "+samplingSink+
                                    "\n\t Mismatch. Matching: format "+!sampleSinkFormatMismatch+", size "+!sampleSinkSizeMismatch +", csUninit "+!sampleColorsinkUninit +", depthStencil "+!sampleSinkDepthStencilMismatch);
        }

        if(DEBUG) {
            System.err.println("FBObject.resetSamplingSink.XX: END\n\tTHIS "+this+",\n\tSINK "+samplingSink+
                               "\n\t Matching: format "+!sampleSinkFormatMismatch+", size "+!sampleSinkSizeMismatch +", csUninit "+!sampleColorsinkUninit +", depthStencil "+!sampleSinkDepthStencilMismatch);
        }
    }

    /**
     * Setting this FBO sampling sink.
     * @param newSamplingSink the new FBO sampling sink to use, or null to remove current sampling sink
     * @return the previous sampling sink or null if none was attached
     * @throws GLException if this FBO doesn't use MSAA or the given sink uses MSAA itself
     */
    public FBObject setSamplingSink(final FBObject newSamplingSink) throws GLException {
        final FBObject prev = samplingSink;
        if( null == newSamplingSink) {
            samplingSink = null;
            samplingColorSink = null;
        } else if( samples > 0 ) {
            if( newSamplingSink.getNumSamples() > 0 ) {
                throw new GLException("SamplingSink FBO cannot use MSAA itself: "+newSamplingSink);
            }
            samplingSink = newSamplingSink;
            samplingColorSink = newSamplingSink.getColorbuffer(0);
        } else {
            throw new GLException("Setting SamplingSink for non MSAA FBO not allowed: "+this);
        }
        samplingSinkDirty = true;
        return prev;
    }

    /**
     * Bind this FBO, i.e. bind write framebuffer to {@link #getWriteFramebuffer()}.
     *
     * <p>If multisampling is used, it sets the read framebuffer to the sampling sink {@link #getWriteFramebuffer()},
     * if full FBO is supported.</p>
     *
     * <p>
     * In case you have attached more than one color buffer,
     * you may want to setup {@link GL2ES3#glDrawBuffers(int, int[], int)}.
     * </p>
     * @param gl the current GL context
     * @throws GLException
     */
    public final void bind(final GL gl) throws GLException {
        if(!bound || fbName != gl.getBoundFramebuffer(GL.GL_FRAMEBUFFER)) {
            checkInitialized();
            if(samples > 0 && fullFBOSupport) {
                // draw to multisampling - read from samplesSink
                gl.glBindFramebuffer(GL2ES3.GL_DRAW_FRAMEBUFFER, getWriteFramebuffer());
                gl.glBindFramebuffer(GL2ES3.GL_READ_FRAMEBUFFER, getReadFramebuffer());
            } else {
                // one for all
                gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, getWriteFramebuffer());
            }

            bound = true;
            samplingSinkDirty = true;
        }
    }

    /**
     * Unbind this FBO, i.e. bind read and write framebuffer to default, see {@link GLBase#getDefaultDrawFramebuffer()}.
     *
     * <p>If full FBO is supported, sets the read and write framebuffer individually to default, hence not disturbing
     * an optional operating MSAA FBO, see {@link GLBase#getDefaultReadFramebuffer()} and {@link GLBase#getDefaultDrawFramebuffer()}</p>
     *
     * @param gl the current GL context
     * @throws GLException
     */
    public final void unbind(final GL gl) throws GLException {
        if(bound) {
            if(fullFBOSupport) {
                // default read/draw buffers, may utilize GLContext/GLDrawable override of
                // GLContext.getDefaultDrawFramebuffer() and GLContext.getDefaultReadFramebuffer()
                gl.glBindFramebuffer(GL2ES3.GL_DRAW_FRAMEBUFFER, 0);
                gl.glBindFramebuffer(GL2ES3.GL_READ_FRAMEBUFFER, 0);
            } else {
                gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0); // default draw buffer
            }
            bound = false;
        }
    }

    /**
     * Method simply marks this FBO unbound w/o interfering w/ the bound framebuffer as perfomed by {@link #unbind(GL)}.
     * <p>
     * Only use this method if a subsequent {@link #unbind(GL)}, {@link #use(GL, TextureAttachment)} or {@link #bind(GL)}
     * follows on <i>any</i> FBO.
     * </p>
     */
    public final void markUnbound() {
        bound = false;
    }

    /**
     * Returns <code>true</code> if framebuffer object is bound via {@link #bind(GL)}, otherwise <code>false</code>.
     * <p>
     * Method verifies the bound state via {@link GL#getBoundFramebuffer(int)}.
     * </p>
     * @param gl the current GL context
     */
    public final boolean isBound(final GL gl) {
        bound = bound &&  fbName != gl.getBoundFramebuffer(GL.GL_FRAMEBUFFER) ;
        return bound;
    }

    /** Returns <code>true</code> if framebuffer object is bound via {@link #bind(GL)}, otherwise <code>false</code>. */
    public final boolean isBound() { return bound; }

    /**
     * If multisampling is being used and flagged dirty by a previous call of {@link #bind(GL)} or after initialization,
     * the msaa-buffers are sampled to it's sink {@link #getSamplingTextureSink()}.
     * <p>
     * Method also resets the sampling sink configuration via {@link #resetSamplingSink(GL)} if used and required.
     * </p>
     * <p>
     * Method is called automatically by {@link #use(GL, TextureAttachment)}.
     * </p>
     * <p>
     * Method always resets the framebuffer binding to default in the end.
     * If full FBO is supported, sets the read and write framebuffer individually to default after sampling, hence not disturbing
     * an optional operating MSAA FBO, see {@link GLBase#getDefaultReadFramebuffer()} and {@link GLBase#getDefaultDrawFramebuffer()}
     * </p>
     * <p>
     * In case you use this FBO w/o the {@link GLFBODrawable} and intend to employ {@link GL#glReadPixels(int, int, int, int, int, int, java.nio.Buffer) glReadPixels(..)}
     * you may want to call {@link GL#glBindFramebuffer(int, int) glBindFramebuffer}({@link GL2ES3#GL_READ_FRAMEBUFFER}, {@link #getReadFramebuffer()});
     * </p>
     * <p>Leaves the FBO unbound.</p>
     *
     * @param gl the current GL context
     * @param ta {@link TextureAttachment} to use, prev. attached w/  {@link #attachTexture2D(GL, int, boolean, int, int, int, int) attachTexture2D(..)}
     * @throws IllegalArgumentException
     */
    public final void syncSamplingSink(final GL gl) {
        markUnbound();
        if(samples>0 && samplingSinkDirty) {
            samplingSinkDirty = false;
            resetSamplingSink(gl);
            checkPreGLError(gl);
            gl.glBindFramebuffer(GL2ES3.GL_READ_FRAMEBUFFER, fbName);
            gl.glBindFramebuffer(GL2ES3.GL_DRAW_FRAMEBUFFER, samplingSink.getWriteFramebuffer());
            ((GL2ES3)gl).glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, // since MSAA is supported, casting to GL2ES3 is OK
                                           GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST);
            checkNoError(null, gl.glGetError(), "FBObject syncSampleSink"); // throws GLException if error
        }
        if(fullFBOSupport) {
            // default read/draw buffers, may utilize GLContext/GLDrawable override of
            // GLContext.getDefaultDrawFramebuffer() and GLContext.getDefaultReadFramebuffer()
            gl.glBindFramebuffer(GL2ES3.GL_DRAW_FRAMEBUFFER, 0);
            gl.glBindFramebuffer(GL2ES3.GL_READ_FRAMEBUFFER, 0);
        } else {
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0); // default draw buffer
        }
    }

    /**
     * {@link #syncSamplingSink(GL) Synchronize the sampling sink} and bind the given {@link TextureAttachment}, if not <code>null</code>.
     *
     * <p>If using a {@link TextureAttachment} and multiple texture units, ensure you call {@link GL#glActiveTexture(int)} first!</p>
     *
     * <p>{@link #syncSamplingSink(GL)} is being called</p>
     *
     * <p>Leaves the FBO unbound!</p>
     *
     * @param gl the current GL context
     * @param ta {@link TextureAttachment} to use, prev. attached w/  {@link #attachTexture2D(GL, int, boolean, int, int, int, int) attachTexture2D(..)},
     *           may be <code>null</code> in case no {@link TextureAttachment} is used.
     * @throws IllegalArgumentException
     */
    public final void use(final GL gl, final TextureAttachment ta) throws IllegalArgumentException {
        syncSamplingSink(gl);
        if( null != ta ) {
            gl.glBindTexture(GL.GL_TEXTURE_2D, ta.getName()); // use it ..
        }
    }

    /**
     * Unbind texture, ie bind 'non' texture 0
     *
     * <p>Leaves the FBO unbound.</p>
     */
    public final void unuse(final GL gl) {
        unbind(gl);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0); // don't use it
    }

    /** @see GL#hasFullFBOSupport() */
    public final boolean hasFullFBOSupport() throws GLException { checkInitialized(); return this.fullFBOSupport; }

    /**
     * Returns <code>true</code> if renderbuffer accepts internal format {@link GL#GL_RGB8} and {@link GL#GL_RGBA8}, otherwise <code>false</code>.
     * @throws GLException if {@link #init(GL)} hasn't been called.
     */
    public final boolean supportsRGBA8() throws GLException { checkInitialized(); return rgba8Avail; }

    /**
     * Returns <code>true</code> if {@link GL#GL_DEPTH_COMPONENT16}, {@link GL#GL_DEPTH_COMPONENT24} or {@link GL#GL_DEPTH_COMPONENT32} is supported, otherwise <code>false</code>.
     * @param bits 16, 24 or 32 bits
     * @throws GLException if {@link #init(GL)} hasn't been called.
     */
    public final boolean supportsDepth(final int bits) throws GLException {
        checkInitialized();
        switch(bits) {
            case 16: return true;
            case 24: return depth24Avail;
            case 32: return depth32Avail;
            default: return false;
        }
    }

    /**
     * Returns <code>true</code> if {@link GL#GL_STENCIL_INDEX1}, {@link GL#GL_STENCIL_INDEX4}, {@link GL#GL_STENCIL_INDEX8} or {@link GL2GL3#GL_STENCIL_INDEX16} is supported, otherwise <code>false</code>.
     * @param bits 1, 4, 8 or 16 bits
     * @throws GLException if {@link #init(GL)} hasn't been called.
     */
    public final boolean supportsStencil(final int bits) throws GLException {
        checkInitialized();
        switch(bits) {
            case  1: return stencil01Avail;
            case  4: return stencil04Avail;
            case  8: return stencil08Avail;
            case 16: return stencil16Avail;
            default: return false;
        }
    }

    /**
     * Returns <code>true</code> if {@link GL#GL_DEPTH24_STENCIL8} is supported, otherwise <code>false</code>.
     * @throws GLException if {@link #init(GL)} hasn't been called.
     */
    public final boolean supportsPackedDepthStencil() throws GLException { checkInitialized(); return packedDepthStencilAvail; }

    /**
     * Returns the maximum number of colorbuffer attachments.
     * @throws GLException if {@link #init(GL)} hasn't been called.
     */
    public final int getMaxColorAttachments() throws GLException { checkInitialized(); return maxColorAttachments; }

    public final int getMaxTextureSize() throws GLException { checkInitialized(); return this.maxTextureSize; }
    public final int getMaxRenderbufferSize() throws GLException { checkInitialized(); return this.maxRenderbufferSize; }

    /** @see GL#getMaxRenderbufferSamples() */
    public final int getMaxSamples() throws GLException { checkInitialized(); return this.maxSamples; }

    /**
     * Returns <code>true</code> if this instance has been initialized with {@link #reset(GL, int, int)}
     * or {@link #reset(GL, int, int, int, boolean)}, otherwise <code>false</code>
     */
    public final boolean isInitialized() { return initialized; }
    /** Returns the width */
    public final int getWidth() { return width; }
    /** Returns the height */
    public final int getHeight() { return height; }
    /** Returns the number of samples for multisampling (MSAA). zero if no multisampling is used. */
    public final int getNumSamples() { return samples; }
    /** Returns the framebuffer name to render to. */
    public final int getWriteFramebuffer() { return fbName; }
    /** Returns the framebuffer name to read from. Depending on multisampling, this may be a different framebuffer. */
    public final int getReadFramebuffer() { return ( samples > 0 ) ? samplingSink.getReadFramebuffer() : fbName; }
    public final int getDefaultReadBuffer() { return GL.GL_COLOR_ATTACHMENT0; }
    /** Return the number of attached {@link Colorbuffer}s */
    public final int getColorbufferCount() { return colorbufferCount; }
    /** Return the number of attached {@link TextureAttachment}s */
    public final int getTextureAttachmentCount() { return textureAttachmentCount; }
    /** Return the stencil {@link RenderAttachment} attachment, if exist. Maybe share the same {@link Attachment#getName()} as {@link #getDepthAttachment()}, if packed depth-stencil is being used. */
    public final RenderAttachment getStencilAttachment() { return stencil; }
    /** Return the depth {@link RenderAttachment} attachment. Maybe share the same {@link Attachment#getName()} as {@link #getStencilAttachment()}, if packed depth-stencil is being used. */
    public final RenderAttachment getDepthAttachment() { return depth; }

    /** Return the complete multisampling {@link FBObject} sink, if using multisampling. */
    public final FBObject getSamplingSinkFBO() { return samplingSink; }

    /** Return the multisampling {@link Colorbuffer} sink, if using multisampling. */
    public final Colorbuffer getSamplingSink() { return samplingColorSink; }

    /**
     * Returns <code>true</code> if the multisampling colorbuffer (msaa-buffer)
     * has been flagged dirty by a previous call of {@link #bind(GL)},
     * otherwise <code>false</code>.
     */
    public final boolean isSamplingBufferDirty() { return samplingSinkDirty; }

    int objectHashCode() { return super.hashCode(); }

    @Override
    public final String toString() {
        final String caps = null != colorbufferAttachments ? Arrays.asList(colorbufferAttachments).toString() : null ;
        return "FBO[name r/w "+fbName+"/"+getReadFramebuffer()+", init "+initialized+", bound "+bound+", size "+width+"x"+height+
               ", samples "+samples+"/"+maxSamples+", depth "+depth+", stencil "+stencil+
               ", colorbuffer attachments: "+colorbufferCount+"/"+maxColorAttachments+", with "+textureAttachmentCount+" textures"+
               ": "+caps+", msaa["+samplingColorSink+", hasSink "+(null != samplingSink)+
               ", dirty "+samplingSinkDirty+"], state "+getStatusString()+", obj "+toHexString(objectHashCode())+"]";
    }

    private final void updateStatus(final GL gl) {
        if( 0 == fbName ) {
            vStatus = -1;
        } else {
            vStatus = gl.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER);
        }
    }
}
