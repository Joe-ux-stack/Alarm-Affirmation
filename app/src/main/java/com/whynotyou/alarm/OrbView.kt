package com.whynotyou.alarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plasma orb: a slightly deformed, mostly transparent glass sphere with a violet/cyan rim and
 * two or three soft ribbons of light drifting inside. [level] drives ribbon brightness and wobble.
 * BREATHE: slow sine. SPEAK: follows setLevel(). STILL: small, calm.
 * Pre-13 devices get the flat concentric-circle version.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { BREATHE, SPEAK, STILL }

    var mode: Mode = Mode.BREATHE
        set(v) { field = v; if (v == Mode.STILL) targetLevel = 0f }

    fun setLevel(l: Float) { targetLevel = l.coerceIn(0f, 1f) }

    private var targetLevel = 0f
    private var level = 0f
    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) try { RuntimeShader(AGSL) } catch (e: Throwable) { null } else null

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t += 1f / 60f
            level += (targetLevel - level) * (if (targetLevel > level) 0.35f else 0.08f)
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    private fun energy(): Float = when (mode) {
        Mode.BREATHE -> 0.35f + 0.25f * ((sin(t * 2 * PI / 4.0).toFloat() + 1f) / 2f)
        Mode.SPEAK -> 0.3f + 0.7f * level
        Mode.STILL -> 0.12f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val e = energy()
        val s = shader
        if (s != null) {
            s.setFloatUniform("iResolution", w, h)
            s.setFloatUniform("iTime", t)
            s.setFloatUniform("iEnergy", e)
            s.setFloatUniform("iScale", if (mode == Mode.STILL) 0.55f else 1f)
            paint.shader = s
            canvas.drawRect(0f, 0f, w, h, paint)
        } else {
            drawFallback(canvas, w, h, e)
        }
    }

    private fun drawFallback(canvas: Canvas, w: Float, h: Float, e: Float) {
        val cx = w / 2f; val cy = h / 2f; val rMax = min(w, h) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A5CFF.toInt() }
        val rings = floatArrayOf(1.0f, 0.72f, 0.48f, 0.30f); val alphas = intArrayOf(14, 30, 56, 115)
        val tt = 0.5f + 0.5f * e
        for (i in rings.indices) { p.alpha = alphas[i]; canvas.drawCircle(cx, cy, rMax * rings[i] * tt, p) }
        p.alpha = 255; canvas.drawCircle(cx, cy, rMax * 0.16f * (0.7f + 0.3f * tt), p)
    }

    companion object {
        private const val AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float iEnergy;
uniform float iScale;

float hash(float2 p){return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453);}
float noise(float2 p){float2 i=floor(p);float2 f=fract(p);float2 u=f*f*f*(f*(f*6.0-15.0)+10.0);
return mix(mix(hash(i),hash(i+float2(1,0)),u.x),mix(hash(i+float2(0,1)),hash(i+float2(1,1)),u.x),u.y);}
float fbm(float2 p){float v=0.0;float a=0.55;for(int i=0;i<3;i++){v+=a*noise(p);p=p*1.9+5.3;a*=0.5;}return v;}
float3 pal(float x){
  float3 a=float3(0.60,0.58,0.95);float3 b=float3(0.36,0.32,0.18);float3 c=float3(1.0,1.0,0.7);float3 d=float3(0.72,0.28,0.78);
  return a+b*cos(6.28318*(c*x+d));
}
// one glowing ribbon of light: distance from a wandering curve in sphere space
float3 ribbon(float2 p,float seed,float t,float3 colA,float3 colB,float w){
  // rotate the ribbon's frame
  float rot=seed*2.1+t*0.05;
  float2 q=float2(cos(rot)*p.x-sin(rot)*p.y,sin(rot)*p.x+cos(rot)*p.y);
  float curve=0.40*sin(q.x*1.6+t*0.5+seed*3.0)+0.12*sin(q.x*3.1-t*0.4+seed)+(fbm(q*1.1+float2(t*0.06,seed))-0.5)*0.35;
  float d=abs(q.y-curve);
  float core=exp(-d*d*(110.0*w));
  float glow=exp(-d*(5.0*w));
  float fade=smoothstep(1.3,0.4,abs(q.x));
  float3 col=mix(colA,colB,0.5+0.5*sin(q.x*3.0+t*0.3+seed));
  return col*(core*0.9+glow*0.45)*fade;
}
half4 main(float2 fragCoord){
  float2 uv=(fragCoord-0.5*iResolution)/min(iResolution.x,iResolution.y);
  float R=(0.30+0.03*iEnergy)*iScale;
  float ang=atan(uv.y,uv.x);
  // more deformed: slow large lobes + faster small ones
  float wob=(sin(ang*2.0+iTime*0.6)*0.6+sin(ang*3.0-iTime*0.45)*0.45+sin(ang*4.0+iTime*0.8)*0.25+sin(ang*7.0-iTime*1.1)*0.1)*(0.006+0.014*iEnergy);
  float r=length(uv);
  float Rw=R+wob;
  float aa=1.5/min(iResolution.x,iResolution.y);
  float inside=1.0-smoothstep(Rw-aa,Rw+aa,r);
  float rr=clamp(r/Rw,0.0,1.0);
  float z=sqrt(max(1.0-rr*rr,0.0));
  float3 n=normalize(float3(uv/Rw,z));
  float ndv=max(n.z,0.0);
  // sphere-space coords (refracted so the ribbons bend with the glass)
  float2 sp=(uv/Rw)*(0.75+0.35*z);
  // ribbons
  float3 pink=float3(1.0,0.35,0.85),cyan=float3(0.35,0.95,1.0),violet=float3(0.55,0.45,1.0),blue=float3(0.3,0.6,1.0);
  float3 lines=float3(0.0);
  lines+=ribbon(sp,0.0,iTime,pink,violet,1.0);
  lines+=ribbon(sp,1.7,iTime*0.9,cyan,blue,1.2);
  lines+=ribbon(sp,3.9,iTime*1.1,violet,cyan,0.7)*0.6;
  lines*=(0.8+0.8*iEnergy)*(0.55+0.9*(1.0-ndv));
  // faint haze inside
  float hz=fbm(sp*1.4+float2(iTime*0.07,-iTime*0.05));
  float3 haze=pal(hz*1.4+iTime*0.02+0.15*sin(ang))*0.10*(0.4+0.6*(1.0-ndv));
  // rim: fully coloured, hue drifts around the circle (violet / magenta / red / blue)
  float f=1.0-ndv;
  float hue=ang+iTime*0.2;
  float3 rimCol=mix(mix(float3(0.50,0.15,1.0),float3(1.0,0.15,0.50),0.5+0.5*sin(hue)),float3(0.25,0.55,1.0),0.5+0.5*sin(2.0*ang-iTime*0.14+1.7)*0.6);
  // broad gradient from the rim fading into the centre + tighter bright band + coloured edge
  float3 rim=rimCol*(pow(f,1.0)*1.1+pow(f,3.0)*1.3+pow(f,10.0)*1.6);
  float edge=smoothstep(Rw-0.010,Rw-0.0015,r)*inside;
  float edgeVar=0.5+0.5*(0.5+0.5*sin(ang*1.0+iTime*0.4));
  rim+=rimCol*1.8*edge*edgeVar;
  // white sheen on the top-right side, like a key light catching the glass
  float side=smoothstep(-0.1,1.0,dot(normalize(uv),normalize(float2(0.72,-0.70))));

  // speculars
  float3 v=float3(0,0,1);
  float3 l1=normalize(float3(-0.5,-0.6,0.65));
  float rl1=max(dot(reflect(-l1,n),v),0.0);
  float3 spec=float3(1.0,0.9,1.0)*pow(rl1,200.0)*0.35;
  float3 col=haze+lines+rim+spec;
  float mx=max(col.r,max(col.g,col.b));
  col*=mix(1.0,1.0/mx,smoothstep(0.9,2.5,mx)*0.85);   // keep hue when bright instead of clipping to white
  col+=float3(1.0,0.98,1.0)*side*(pow(f,1.5)*0.9+pow(f,5.0)*1.3+edge*1.6);   // white sheen, added after so it stays white
  // bloom outside
  float halo=exp(-max(r-Rw,0.0)*10.0)*(0.16+0.30*iEnergy);
  float3 haloCol=rimCol*halo;
  // alpha: mostly transparent centre, additive light on top
  float innerA=0.06+0.94*pow(f,1.0);
  float lum=dot(lines+spec,float3(0.33));
  float alpha=max(inside*clamp(innerA+lum,0.0,1.0),halo);
  float3 outc=col*inside+haloCol*(1.0-inside);
  return half4(half3(outc*alpha),half(alpha));
}
"""
    }
}
