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
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F4F8.toInt() }
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
  for (int i = 0; i < 3; i++) { v += a * noise(p); p = p * 2.0 + 7.7; a *= 0.5; }
  return v;
}

half4 main(float2 fragCoord) {
  float2 uv = (fragCoord - 0.5 * iResolution) / min(iResolution.x, iResolution.y);
  float r = length(uv);
  float ang = atan(uv.y, uv.x);

  // radius kept inside the view: max ~0.42 of the short side
  float base = (0.26 + 0.07 * iEnergy) * iScale;
  float wob = (fbm(float2(cos(ang), sin(ang)) * 1.6 + iTime * 0.22) - 0.5) * (0.03 + 0.05 * iEnergy);
  float radius = base + wob;

  float edge = 1.0 - smoothstep(radius - 0.006, radius + 0.006, r);

  // glass body: dark centre, bright towards the rim
  float d = clamp(r / max(radius, 0.001), 0.0, 1.0);
  float body = pow(d, 2.2);
  float3 white = float3(1.0);
  float3 blue  = float3(0.62, 0.74, 1.0);
  float3 col = mix(float3(0.06, 0.07, 0.10), white, body * 0.75);

  // slow internal sheen
  float sheen = fbm(uv * 1.8 + float2(iTime * 0.10, -iTime * 0.07));
  col += blue * sheen * 0.22;

  // rim light
  float rim = smoothstep(radius - 0.05, radius, r) * edge;
  col += mix(white, blue, 0.4) * rim * (0.6 + 0.6 * iEnergy);

  // specular
  float2 hp = float2(-0.10, -0.12);
  float spec = exp(-dot(uv - hp, uv - hp) * 220.0);
  col += white * spec * (0.5 + 0.3 * iEnergy);

  // outer halo, soft and short
  float halo = exp(-max(r - radius, 0.0) * 22.0) * (0.18 + 0.25 * iEnergy);

  float alpha = max(edge, halo);
  float3 outc = col * edge + blue * halo * (1.0 - edge);
  return half4(half3(outc * alpha), half(alpha));
}
"""
    }
}
