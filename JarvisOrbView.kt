package com.chtai.assistant

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class JarvisOrbView(context: Context) : GLSurfaceView(context) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }
    private val scene = OrbRenderer()
    init { setEGLContextClientVersion(2); setRenderer(scene); renderMode=RENDERMODE_CONTINUOUSLY; keepScreenOn=false; setLayerType(View.LAYER_TYPE_HARDWARE,null) }
    fun setAudioLevel(level:Float) { scene.energy=level.coerceIn(0f,1f) }
    fun setMode(next:Mode) { scene.mode=next }

    private class OrbRenderer : Renderer {
        @Volatile var mode=Mode.IDLE
        @Volatile var energy=0f
        private var program=0
        private var vertices:FloatBuffer?=null
        private var indices:java.nio.ShortBuffer?=null
        private var count=0
        private val projection=FloatArray(16); private val view=FloatArray(16); private val model=FloatArray(16)
        private val mvp=FloatArray(16); private var position=0; private var matrix=0; private var color=0
        private var timeLoc=0; private var energyLoc=0; private var modeLoc=0
        private val start=System.nanoTime()
        private val vs="""uniform mat4 uMVP; uniform float uTime; uniform float uEnergy; attribute vec3 aPosition; varying vec3 vN; varying vec3 vP; void main(){float pulse=1.0+0.025*sin(uTime*2.0)+uEnergy*0.10; vec3 p=aPosition*pulse; vN=normalize(aPosition); vP=p; gl_Position=uMVP*vec4(p,1.0);}"""
        private val fs="""precision mediump float; uniform vec4 uColor; uniform float uTime; uniform float uEnergy; uniform float uMode; varying vec3 vN; varying vec3 vP; void main(){vec3 l=normalize(vec3(-0.45,0.65,0.9)); float d=max(dot(normalize(vN),l),0.0); float rim=pow(1.0-max(dot(normalize(vN),vec3(0.0,0.0,1.0)),0.0),2.2); float scan=0.94+0.06*sin(vP.y*35.0+uTime*2.5); vec3 base=uColor.rgb*(0.30+0.78*d)*scan+uColor.rgb*rim*0.65; gl_FragColor=vec4(base+uColor.rgb*(0.08+uEnergy*0.20+(uMode>1.5?0.12:0.0)),1.0);}"""
        override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
            GLES20.glClearColor(0.008f,0.012f,0.035f,1f); GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            program=link(vs,fs); position=GLES20.glGetAttribLocation(program,"aPosition"); matrix=GLES20.glGetUniformLocation(program,"uMVP")
            color=GLES20.glGetUniformLocation(program,"uColor"); timeLoc=GLES20.glGetUniformLocation(program,"uTime")
            energyLoc=GLES20.glGetUniformLocation(program,"uEnergy"); modeLoc=GLES20.glGetUniformLocation(program,"uMode")
            sphere(40,56); Matrix.setLookAtM(view,0,0f,0f,3.35f,0f,0f,0f,0f,1f,0f)
        }
        override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) { GLES20.glViewport(0,0,w,h); val r=w.toFloat()/h.coerceAtLeast(1); Matrix.frustumM(projection,0,-r,r,-1f,1f,2f,8f) }
        override fun onDrawFrame(gl:GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT); if(program==0)return
            val t=(System.nanoTime()-start)/1_000_000_000f
            Matrix.setIdentityM(model,0); Matrix.rotateM(model,0,t*13f,0.2f,1f,0.1f)
            val mv=FloatArray(16); Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,projection,0,mv,0)
            val c=when(mode){Mode.LISTENING->floatArrayOf(.12f,.92f,.72f,1f);Mode.THINKING->floatArrayOf(.56f,.40f,1f,1f);Mode.SPEAKING->floatArrayOf(.12f,.70f,1f,1f);Mode.IDLE->floatArrayOf(.18f,.62f,.95f,1f)}
            GLES20.glUseProgram(program); GLES20.glUniformMatrix4fv(matrix,1,false,mvp,0); GLES20.glUniform4fv(color,1,c,0)
            GLES20.glUniform1f(timeLoc,t); GLES20.glUniform1f(energyLoc,energy); GLES20.glUniform1f(modeLoc,mode.ordinal.toFloat())
            vertices?.position(0); GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,12,vertices!!)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES,count,GLES20.GL_UNSIGNED_SHORT,indices!!); GLES20.glDisableVertexAttribArray(position)
        }
        private fun sphere(stacks:Int,slices:Int) {
            val v=ArrayList<Float>(); val ix=ArrayList<Short>()
            for(i in 0..stacks){val p=Math.PI*i/stacks;for(j in 0..slices){val a=2*Math.PI*j/slices;v.add((sin(p)*cos(a)).toFloat());v.add(kotlin.math.cos(p).toFloat());v.add((sin(p)*sin(a)).toFloat())}}
            for(i in 0 until stacks)for(j in 0 until slices){val a=i*(slices+1)+j;val b=a+slices+1;ix.add(a.toShort());ix.add(b.toShort());ix.add((a+1).toShort());ix.add((a+1).toShort());ix.add(b.toShort());ix.add((b+1).toShort())}
            val vb=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();v.forEach{vb.put(it)};vb.position(0);vertices=vb
            val ib=ByteBuffer.allocateDirect(ix.size*2).order(ByteOrder.nativeOrder()).asShortBuffer();ix.forEach{ib.put(it)};ib.position(0);indices=ib;count=ix.size
        }
        private fun shader(type:Int,src:String):Int{val s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);val ok=IntArray(1);GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw IllegalStateException(GLES20.glGetShaderInfoLog(s));return s}
        private fun link(v:String,f:String):Int{val p=GLES20.glCreateProgram();GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,v));GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,f));GLES20.glLinkProgram(p);val ok=IntArray(1);GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw IllegalStateException(GLES20.glGetProgramInfoLog(p));return p}
    }
}