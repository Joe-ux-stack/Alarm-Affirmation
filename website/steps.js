/* "How it works": one step in focus. A bar fills over 6 s, then the next step takes over.
   The faded steps are clickable. Pauses on hover and when scrolled away. */
(function () {
  function init(root) {
    var steps = Array.prototype.slice.call(root.querySelectorAll(".step"));
    if (steps.length < 2) return;
    root.classList.add("js");
    var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
    var cur = 0;
    function show(i) {
      cur = (i + steps.length) % steps.length;
      steps.forEach(function (s, k) {
        var on = k === cur;
        s.classList.toggle("active", on);
        s.setAttribute("aria-current", on ? "step" : "false");
        var bar = s.querySelector(".step-bar i");
        if (bar) { bar.style.animation = "none"; void bar.offsetWidth; bar.style.animation = ""; }
      });
    }
    steps.forEach(function (s, k) {
      s.setAttribute("tabindex", "0"); s.setAttribute("role", "button");
      s.addEventListener("click", function () { if (k !== cur) show(k); });
      s.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); show(k); } });
      var bar = s.querySelector(".step-bar i");
      if (bar && !reduce) bar.addEventListener("animationend", function () { if (k === cur) show(cur + 1); });
    });
    root.addEventListener("mouseenter", function () { root.classList.add("paused"); });
    root.addEventListener("mouseleave", function () { root.classList.remove("paused"); });
    if ("IntersectionObserver" in window) new IntersectionObserver(function (en) {
      root.classList.toggle("paused", !en[0].isIntersecting);
    }, { threshold: 0.05 }).observe(root);
    show(0);
  }
  function go() { Array.prototype.forEach.call(document.querySelectorAll("[data-steps]"), init); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", go); else go();
})();
