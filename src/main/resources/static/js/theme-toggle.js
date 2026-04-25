"use strict";
{
  const ORDER = ["system", "light", "dark"];
  const LABELS = {
    system: "Theme: system (click to switch to light)",
    light: "Theme: light (click to switch to dark)",
    dark: "Theme: dark (click to switch to system)",
  };

  const current = () => {
    const t = document.documentElement.dataset.theme;
    return t === "light" || t === "dark" ? t : "system";
  };

  const applyHighlightMedia = (theme) => {
    const light = document.getElementById("hl-theme-light");
    const dark = document.getElementById("hl-theme-dark");
    if (!light || !dark) return;
    if (theme === "light") {
      light.media = "all";
      dark.media = "not all";
    } else if (theme === "dark") {
      light.media = "not all";
      dark.media = "all";
    } else {
      light.media = "(prefers-color-scheme: light)";
      dark.media = "(prefers-color-scheme: dark)";
    }
  };

  const updateButtons = () => {
    const cur = current();
    for (const btn of document.querySelectorAll(".theme-toggle")) {
      btn.setAttribute("aria-label", LABELS[cur]);
      btn.setAttribute("title", LABELS[cur]);
    }
  };

  const apply = (theme) => {
    if (theme === "system") {
      delete document.documentElement.dataset.theme;
      try { localStorage.removeItem("theme"); } catch { /* storage unavailable */ }
    } else {
      document.documentElement.dataset.theme = theme;
      try { localStorage.setItem("theme", theme); } catch { /* storage unavailable */ }
    }
    applyHighlightMedia(theme);
    updateButtons();
  };

  document.addEventListener("click", (e) => {
    const btn = e.target?.closest?.(".theme-toggle");
    if (!btn) return;
    e.preventDefault();
    const next = ORDER[(ORDER.indexOf(current()) + 1) % ORDER.length];
    apply(next);
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", updateButtons);
  } else {
    updateButtons();
  }

  document.addEventListener("htmx:afterSwap", updateButtons);
}
