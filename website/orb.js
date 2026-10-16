/* The app's orb, running live: the same shader as the Android app (OrbView.kt), in WebGL2.
   Every element with class "orb" becomes a canvas. Without WebGL it keeps the still image. */
(function () {
  var FRAG = "#version 300 es\nprecision highp float;\nout vec4 o;\n\nuniform vec2 iResolution;\nuniform float iTime;\nuniform float iEnergy;\nuniform float iScale;\n\nfloat hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\nfloat noise(vec2 p){vec2 i=floor(p);vec2 f=fract(p);vec2 u=f*f*f*(f*(f*6.0-15.0)+10.0);\nreturn mix(mix(hash(i),hash(i+vec2(1,0)),u.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),u.x),u.y);}\nfloat fbm(vec2 p){float v=0.0;float a=0.55;for(int i=0;i<3;i++){v+=a*noise(p);p=p*1.9+5.3;a*=0.5;}return v;}\n// one soft ribbon of light: distance from a wandering curve in sphere space\nvec3 ribbon(vec2 p,float seed,float t,vec3 colA,vec3 colB,float w){\n  float rot=seed;\n  vec2 q=vec2(cos(rot)*p.x-sin(rot)*p.y,sin(rot)*p.x+cos(rot)*p.y);\n  float curve=0.40*sin(q.x*1.6+t*0.5+seed*3.0)+0.12*sin(q.x*3.1-t*0.4+seed)+(fbm(q*1.1+vec2(t*0.06,seed))-0.5)*0.35;\n  float d=abs(q.y-curve);\n  float core=exp(-d*d*(110.0*w));\n  float glow=exp(-d*(5.0*w));\n  float fade=smoothstep(1.3,0.4,abs(q.x));\n  vec3 col=mix(colA,colB,0.5+0.5*sin(q.x*3.0+t*0.3+seed));\n  return col*(core*0.9+glow*0.45)*fade;\n}\nvec4 orb(vec2 fragCoord){\n  vec2 uv=(fragCoord-0.5*iResolution)/min(iResolution.x,iResolution.y);\n  float R=(0.27+0.045*iEnergy)*iScale;\n  float ang=atan(uv.y,uv.x);\n  // strongly deformed: big slow lobes\n  float wob=(sin(ang*2.0+iTime*0.5)*0.5+sin(ang*3.0-iTime*0.4)*0.6+sin(ang*5.0+iTime*0.7)*0.25+sin(ang*6.0-iTime*0.9)*0.1)*(0.010+0.060*iEnergy);\n  float r=length(uv);\n  float Rw=R+wob;\n  float aa=1.5/min(iResolution.x,iResolution.y);\n  float inside=1.0-smoothstep(Rw-aa,Rw+aa,r);\n  float rr=clamp(r/Rw,0.0,1.0);\n  float z=sqrt(max(1.0-rr*rr,0.0));\n  vec3 n=normalize(vec3(uv/Rw,z));\n  float ndv=max(n.z,0.0);\n  float f=1.0-ndv;\n  // top-light: upper half lit, lower half falls to black\n  float up=smoothstep(0.9,-0.6,uv.y/Rw);          // 1 at top, 0 at bottom\n  float lit=0.12+0.88*up;\n  vec2 sp=(uv/Rw)*(0.75+0.35*z);\n  // two crossing ribbons, kept in the upper half\n  vec3 pink=vec3(1.0,0.30,0.85),cyan=vec3(0.30,0.90,1.0),violet=vec3(0.55,0.35,1.0),blue=vec3(0.25,0.50,1.0);\n  vec2 spU=sp+vec2(0.0,0.45);\n  vec3 lines=vec3(0.0);\n  lines+=ribbon(spU,-0.55,iTime,pink,violet,1.0);\n  lines+=ribbon(spU,0.55,iTime*0.9,cyan,blue,1.1);\n  lines*=(0.35+1.8*iEnergy)*(0.5+0.9*f)*smoothstep(-0.9,0.3,-uv.y/Rw);\n  // rim colour fixed by side: magenta/violet on the left, blue/cyan on the right\n  float side=0.5+0.5*cos(ang+iTime*0.12);           // 1 right, 0 left\n  vec3 rimCol=mix(mix(vec3(0.85,0.15,0.95),vec3(0.45,0.20,1.0),0.5+0.5*sin(uv.y/Rw*2.5)),\n                  mix(vec3(0.15,0.45,1.0),vec3(0.25,0.85,1.0),0.5+0.5*sin(uv.y/Rw*2.5+1.0)),side);\n  vec3 rim=rimCol*(pow(f,1.0)*1.0*lit+pow(f,3.0)*1.4*(0.45+0.55*lit)+pow(f,10.0)*1.3);\n  float edge=smoothstep(Rw-0.010,Rw-0.0015,r)*inside;\n  rim+=rimCol*2.0*edge*(0.7+0.3*lit);\n  // faint haze, upper half only\n  vec3 haze=mix(vec3(0.35,0.10,0.6),vec3(0.1,0.3,0.7),side)*0.10*f*lit;\n  vec3 col=haze+lines+rim;\n  float mx=max(col.r,max(col.g,col.b));\n  col*=mix(1.0,1.0/mx,smoothstep(0.9,2.5,mx)*0.85);\n  // white sheen top-right\n  float sheenDir=smoothstep(0.55,1.0,dot(normalize(uv),normalize(vec2(0.62,-0.78))));\n  col+=vec3(1.0,0.98,1.0)*sheenDir*(pow(f,2.0)*0.4+pow(f,6.0)*0.8+edge*0.8);\n  // bloom outside\n  float halo=exp(-max(r-Rw,0.0)*10.0)*(0.10+0.45*iEnergy)*(0.4+0.6*lit);\n  vec3 haloCol=rimCol*halo;\n  float innerA=0.04+0.96*pow(f,1.0);\n  float lum=dot(lines,vec3(0.33));\n  float alpha=max(inside*clamp(innerA+lum,0.0,1.0),halo);\n  vec3 outc=col*inside+haloCol*(1.0-inside);\n  return vec4(vec3(outc*alpha),float(alpha));\n}\n\nvoid main(){ vec2 fc=vec2(gl_FragCoord.x, iResolution.y-gl_FragCoord.y); o=orb(fc); }\n";
  var VERT = "#version 300 es\nin vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}";
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;

  function make(el) {
    var c = document.createElement("canvas");
    c.setAttribute("aria-hidden", "true");
    c.style.cssText = "position:absolute;inset:-18%;width:136%;height:136%";
    var gl = c.getContext("webgl2", { premultipliedAlpha: true, alpha: true, antialias: true });
    if (!gl) return;
    function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
    var pr = gl.createProgram();
    try { gl.attachShader(pr, sh(gl.VERTEX_SHADER, VERT)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FRAG)); }
    catch (e) { return; }
    gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) return;
    gl.useProgram(pr);
    var b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
    var loc = gl.getAttribLocation(pr, "p"); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    var uRes = gl.getUniformLocation(pr, "iResolution"), uT = gl.getUniformLocation(pr, "iTime"),
        uE = gl.getUniformLocation(pr, "iEnergy"), uS = gl.getUniformLocation(pr, "iScale");
    el.style.background = "none"; el.style.animation = "none"; el.appendChild(c);

    var visible = true, t = Math.random() * 20, last = performance.now();
    function size() {
      var dpr = Math.min(window.devicePixelRatio || 1, 2), w = Math.round(c.clientWidth * dpr), h = Math.round(c.clientHeight * dpr);
      if (c.width !== w || c.height !== h) { c.width = w; c.height = h; gl.viewport(0, 0, w, h); }
      gl.uniform2f(uRes, w, h);
    }
    function frame(now) {
      var dt = Math.min((now - last) / 1000, 0.05); last = now; t += dt;
      // same "breathe" as the app: energy 0.3..0.6 over 4 s; the orb fills ~74% of the canvas
      var e = 0.3 + 0.3 * ((Math.sin(t * 2 * Math.PI / 4) + 1) / 2);
      size(); gl.uniform1f(uT, t); gl.uniform1f(uE, e); gl.uniform1f(uS, 1.25);
      gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      if (visible && !reduce) requestAnimationFrame(frame);
    }
    if ("IntersectionObserver" in window) new IntersectionObserver(function (en) {
      var v = en[0].isIntersecting; if (v && !visible) { visible = true; last = performance.now(); requestAnimationFrame(frame); } visible = v;
    }).observe(el);
    requestAnimationFrame(frame);
  }
  function init() { Array.prototype.forEach.call(document.querySelectorAll(".orb"), make); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();
})();
