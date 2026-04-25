"use strict";
// Back-to-top button. Hidden at the top of the page; revealed once the user
// has scrolled past a threshold. Click smooth-scrolls back to the top.
//
// The button lives on the outer layout, but hx-boost swaps <body>'s
// innerHTML so the button is destroyed and recreated on each boosted
// navigation. Keep the scroll listener at the window level (bound once) and
// re-query the current button each tick — this avoids retaining references
// to detached nodes across swaps. The per-button click wiring is guarded by
// dataset.backToTopInit so the htmx:afterSwap rescan is a no-op for an
// already-wired button.
{
  const THRESHOLD = 400;

  const update = () => {
    const btn = document.querySelector(".back-to-top");
    if (!btn) return;
    btn.hidden = window.scrollY <= THRESHOLD;
  };

  const init = (root) => {
    const scope = root?.querySelectorAll ? root : document;
    const btn = scope.matches?.(".back-to-top")
      ? scope
      : scope.querySelector(".back-to-top");
    if (!btn || btn.dataset.backToTopInit === "1") return;
    btn.dataset.backToTopInit = "1";
    btn.addEventListener("click", () => {
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
    update();
  };

  window.addEventListener("scroll", update, { passive: true });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => init(document));
  } else {
    init(document);
  }

  document.body.addEventListener("htmx:afterSwap", (e) => init(e.target));
}
