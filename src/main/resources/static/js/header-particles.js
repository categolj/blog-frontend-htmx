"use strict";
// Pops bubble-like particles across the full-width header background while any
// htmx request is in flight, as a subtle global loading indicator.
//
// State is mirrored onto :root[data-htmx-loading] so it survives hx-boost body
// swaps. The clear-side listens to htmx:afterSettle / error / timeout rather
// than htmx:afterRequest, for the same reason search-indicator.js does — the
// triggering element may be detached by the swap before afterRequest fires, so
// the event never bubbles to document.
//
// The canvas is lazily (re)inserted into .site-header on each animation tick,
// which covers the case where .site-header is recreated by a body swap.
{
  const SPAWN_INTERVAL_MS = 28;
  const MAX_PARTICLES = 80;
  const PARTICLE_TTL_MIN = 600;
  const PARTICLE_TTL_JITTER = 500;
  const POP_PHASE = 0.72;
  const PARTICLE_MIN_R = 3;
  const PARTICLE_R_JITTER = 5;
  const FILL_ALPHA = 0.55;
  const RING_ALPHA = 0.75;

  const particles = [];
  let rafId = null;
  let lastSpawn = 0;
  let loading = false;

  const readColors = () => {
    const style = getComputedStyle(document.documentElement);
    const accent = style.getPropertyValue("--accent").trim() || "#0060d0";
    const muted = style.getPropertyValue("--fg-muted").trim() || "#888";
    return [accent, accent, muted];
  };

  const ensureCanvas = () => {
    const header = document.querySelector(".site-header");
    if (!header) return null;
    let canvas = header.querySelector(":scope > .header-particles");
    if (!canvas) {
      canvas = document.createElement("canvas");
      canvas.className = "header-particles";
      canvas.setAttribute("aria-hidden", "true");
      header.prepend(canvas);
    }
    return canvas;
  };

  const syncSize = (canvas) => {
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return false;
    const dpr = window.devicePixelRatio || 1;
    const w = Math.round(rect.width * dpr);
    const h = Math.round(rect.height * dpr);
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return true;
  };

  const spawn = (ts, rect) => {
    if (particles.length >= MAX_PARTICLES) return;
    const colors = readColors();
    particles.push({
      x: Math.random() * rect.width,
      y: Math.random() * rect.height,
      maxR: PARTICLE_MIN_R + Math.random() * PARTICLE_R_JITTER,
      ttl: PARTICLE_TTL_MIN + Math.random() * PARTICLE_TTL_JITTER,
      // Use the frame timestamp so `ts - born` can never go negative in the
      // same frame. Mixing performance.now() here with rAF's ts caused
      // IndexSizeError from Canvas arc() being fed a tiny negative radius.
      born: ts,
      color: colors[Math.floor(Math.random() * colors.length)],
    });
  };

  const step = (ts) => {
    const canvas = ensureCanvas();
    if (!canvas) {
      rafId = null;
      return;
    }
    if (!syncSize(canvas)) {
      // Not laid out yet (e.g. display: none); retry next frame if still active.
      rafId = loading ? requestAnimationFrame(step) : null;
      return;
    }
    const rect = canvas.getBoundingClientRect();
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, rect.width, rect.height);

    if (loading && ts - lastSpawn > SPAWN_INTERVAL_MS) {
      spawn(ts, rect);
      lastSpawn = ts;
    }

    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i];
      const progress = Math.max(0, (ts - p.born) / p.ttl);
      if (progress >= 1) {
        particles.splice(i, 1);
        continue;
      }
      const grow = progress < POP_PHASE ? progress / POP_PHASE : 1;
      const fade = progress < POP_PHASE ? 1 : 1 - (progress - POP_PHASE) / (1 - POP_PHASE);
      ctx.fillStyle = p.color;
      ctx.globalAlpha = FILL_ALPHA * fade;
      ctx.beginPath();
      ctx.arc(p.x, p.y, Math.max(0, p.maxR * grow), 0, Math.PI * 2);
      ctx.fill();
      if (progress > POP_PHASE) {
        const popT = (progress - POP_PHASE) / (1 - POP_PHASE);
        ctx.strokeStyle = p.color;
        ctx.globalAlpha = RING_ALPHA * (1 - popT);
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.maxR * (1 + popT * 1.8), 0, Math.PI * 2);
        ctx.stroke();
      }
    }
    ctx.globalAlpha = 1;

    if (loading || particles.length > 0) {
      rafId = requestAnimationFrame(step);
    } else {
      rafId = null;
    }
  };

  const startLoop = () => {
    if (rafId != null) return;
    rafId = requestAnimationFrame(step);
  };

  document.addEventListener("htmx:beforeRequest", () => {
    loading = true;
    document.documentElement.dataset.htmxLoading = "1";
    startLoop();
  });

  const finish = () => {
    loading = false;
    delete document.documentElement.dataset.htmxLoading;
    // Keep the loop running until in-flight particles finish their pop.
    startLoop();
  };
  for (const ev of [
    "htmx:afterSettle",
    "htmx:responseError",
    "htmx:sendError",
    "htmx:timeout",
    "htmx:swapError",
  ]) {
    document.addEventListener(ev, finish);
  }
}
