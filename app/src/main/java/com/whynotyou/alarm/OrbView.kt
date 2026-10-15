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
 * Iridescent glass orb. On Android 13+ it's an AGSL shader: a refractive sphere (per-channel
 * dispersion, fresnel reflection, two speculars) over a drifting violet/cyan/pink colour field, plus bloom. [level] (0..1) drives wobble + brightness.
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
  float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  return mix(mix(hash(i), hash(i + float2(1,0)), u.x), mix(hash(i + float2(0,1)), hash(i + float2(1,1)), u.x), u.y);
}
float fbm(float2 p) {
  float v = 0.0; float a = 0.55;
  for (int i = 0; i < 3; i++) { v += a * noise(p); p = p * 1.9 + 5.3; a *= 0.5; }
  return v;
}
float3 pal(float x) {
  float3 a = float3(0.60, 0.58, 0.95); float3 b = float3(0.36, 0.32, 0.18);
  float3 c = float3(1.0, 1.0, 0.7);    float3 d = float3(0.72, 0.28, 0.78);
  return a + b * cos(6.28318 * (c * x + d));
}
// environment seen through / reflected by the glass: soft drifting colour field + a softbox top-left
float3 env(float2 p, float t) {
  float n1 = fbm(p * 1.8 + float2(t * 0.09, -t * 0.06));
  float n2 = fbm(p * 1.9 - float2(t * 0.05, t * 0.10) + n1 * 1.4);
  float3 c = pal(n2 * 1.5 + t * 0.02 + 0.25);
  float2 dl = p - float2(-0.9, -1.1);
  c += float3(0.9, 0.95, 1.0) * exp(-dot(dl, dl) * 0.9) * 0.9;
  return c;
}

half4 main(float2 fragCoord) {
  float mn = min(iResolution.x, iResolution.y);
  float2 uv = (fragCoord - 0.5 * iResolution) / mn;
  float R = (0.30 + 0.035 * iEnergy) * iScale;
  float ang = atan(uv.y, uv.x);
  float wob = (sin(ang * 2.0 + iTime * 0.7) * 0.5 + sin(ang * 3.0 - iTime * 0.5) * 0.3 + sin(ang * 5.0 + iTime * 0.9) * 0.1) * (0.005 + 0.016 * iEnergy);
  float r = length(uv);
  float Rw = R + wob;
  float aa = 1.5 / mn;
  float inside = 1.0 - smoothstep(Rw - aa, Rw + aa, r);
  float rr = clamp(r / Rw, 0.0, 1.0);
  float z = sqrt(max(1.0 - rr * rr, 0.0));
  float3 n = normalize(float3(uv / Rw, z));
  float3 v = float3(0.0, 0.0, 1.0);
  float ndv = max(dot(n, v), 0.0);
  float fres = 0.04 + 0.96 * pow(1.0 - ndv, 3.0);

  // refraction with chromatic dispersion (three IORs)
  float3 I = float3(0.0, 0.0, -1.0);
  float2 rR = refract(I, n, 1.0 / 1.42).xy;
  float2 rG = refract(I, n, 1.0 / 1.46).xy;
  float2 rB = refract(I, n, 1.0 / 1.50).xy;
  float2 base = uv / Rw * 0.8;
  float depth = 1.6;
  float3 tr = float3(env(base + rR * depth, iTime).r, env(base + rG * depth, iTime).g, env(base + rB * depth, iTime).b);
  tr = mix(tr, tr * float3(0.55, 0.5, 0.9), 0.35 * (1.0 - rr));

  // reflection of the environment
  float3 rf = reflect(-v, n);
  float3 refl = env(rf.xy * 1.4 + float2(0.0, -0.3), iTime * 0.7) * 0.9;
  float3 col = mix(tr, refl, fres);

  // chromatic fresnel rim
  col += float3(0.6, 0.8, 1.0) * pow(1.0 - ndv, 5.0) * (0.9 + 0.5 * iEnergy);
  col += float3(1.0, 0.55, 0.9) * pow(1.0 - ndv, 9.0) * 0.7;

  // speculars
  float3 l1 = normalize(float3(-0.5, -0.6, 0.65));
  float3 l2 = normalize(float3(0.6, 0.5, 0.45));
  float rl1 = max(dot(reflect(-l1, n), v), 0.0);
  col += float3(1.0) * (pow(rl1, 160.0) * 1.1 + pow(rl1, 16.0) * 0.12);
  col += float3(0.95, 0.9, 1.0) * pow(max(dot(reflect(-l2, n), v), 0.0), 48.0) * 0.35;

  // weight: slight darkening at the lower rim
  col *= 1.0 - 0.25 * smoothstep(0.6, 1.0, rr) * smoothstep(-0.2, 0.8, uv.y / Rw);

  // bloom
  float halo = exp(-max(r - Rw, 0.0) * 11.0) * (0.18 + 0.30 * iEnergy);
  float3 haloCol = pal(iTime * 0.02 + 0.3) * halo;
  float alpha = max(inside, halo);
  float3 outc = col * inside + haloCol * (1.0 - inside);
  return half4(half3(outc * alpha), half(alpha));
}
"""
    }
}
