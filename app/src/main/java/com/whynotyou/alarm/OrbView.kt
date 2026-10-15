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
 * Iridescent blob. On Android 13+ it's an AGSL shader: a noise-wobbled sphere with drifting
 * hue, a rim glow and a specular highlight. [level] (0..1) drives wobble + brightness.
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

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
  float2 i = floor(p); float2 f = fract(p);
  float2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + float2(1,0)), u.x), mix(hash(i + float2(0,1)), hash(i + float2(1,1)), u.x), u.y);
}
float fbm(float2 p) {
  float v = 0.0; float a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + 11.3; a *= 0.5; }
  return v;
}
float3 palette(float x) {
  // deep violet -> electric blue -> cyan -> pink, drifting
  float3 a = float3(0.52, 0.42, 0.78);
  float3 b = float3(0.45, 0.45, 0.35);
  float3 c = float3(1.0, 1.0, 1.0);
  float3 d = float3(0.60, 0.15, 0.80);
  return a + b * cos(6.28318 * (c * x + d));
}

half4 main(float2 fragCoord) {
  float2 uv = (fragCoord - 0.5 * iResolution) / min(iResolution.x, iResolution.y);
  float r = length(uv);
  float ang = atan(uv.y, uv.x);

  // wobbly radius: base + low-freq noise around the rim, more wobble with energy
  float wob = fbm(float2(ang * 1.2 + iTime * 0.25, iTime * 0.35)) - 0.5;
  float radius = (0.30 + 0.07 * iEnergy) * iScale + wob * (0.035 + 0.06 * iEnergy);

  float edge = smoothstep(radius, radius - 0.012, r);
  float rim  = smoothstep(radius - 0.06, radius, r) * edge;

  // internal color flow
  float2 q = uv * 2.2;
  float n1 = fbm(q + float2(iTime * 0.18, -iTime * 0.12));
  float n2 = fbm(q * 1.7 - float2(iTime * 0.09, iTime * 0.21) + n1);
  float3 col = palette(n2 + iTime * 0.05 + r * 0.6);

  // center darker, rim brighter (hollow glass feel)
  float depth = smoothstep(0.0, radius, r);
  col = mix(col * 0.35, col, depth);

  // rim glow
  col += float3(0.55, 0.65, 1.0) * rim * (0.9 + 0.8 * iEnergy);

  // specular highlight, drifting
  float2 hp = float2(0.12, -0.14) + 0.03 * float2(sin(iTime * 0.7), cos(iTime * 0.5));
  float spec = exp(-dot(uv - hp, uv - hp) * 180.0);
  col += float3(1.0) * spec * (0.6 + 0.4 * iEnergy);

  // soft outer halo
  float halo = exp(-max(r - radius, 0.0) * 10.0) * (0.35 + 0.5 * iEnergy);
  float3 haloCol = float3(0.40, 0.30, 1.0) * halo;

  float alpha = max(edge, halo * 0.9);
  float3 outc = col * edge + haloCol * (1.0 - edge);
  return half4(half3(outc * alpha), half(alpha));
}
"""
    }
}
