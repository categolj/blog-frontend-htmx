"use strict";
// Mobile hamburger toggle for the header nav. The button is hidden at desktop
// widths via CSS — this script only wires the open/close behavior. Open state
// lives on the nav as data-nav-open and is mirrored via aria-expanded on the
// button so assistive tech sees the same state CSS reads.
{
  const navOf = (toggle) => {
    const id = toggle.getAttribute("aria-controls");
    return id ? document.getElementById(id) : null;
  };

  const close = (toggle, nav = navOf(toggle)) => {
    toggle.setAttribute("aria-expanded", "false");
    if (nav) delete nav.dataset.navOpen;
  };

  const open = (toggle, nav = navOf(toggle)) => {
    toggle.setAttribute("aria-expanded", "true");
    if (nav) nav.dataset.navOpen = "true";
  };

  const init = (root) => {
    const scope = root?.querySelectorAll ? root : document;
    const toggles = scope.matches?.(".nav-toggle")
      ? [scope]
      : scope.querySelectorAll(".nav-toggle");
    for (const toggle of toggles) {
      if (toggle.dataset.navToggleInit === "1") continue;
      toggle.dataset.navToggleInit = "1";
      toggle.addEventListener("click", () => {
        if (toggle.getAttribute("aria-expanded") === "true") {
          close(toggle);
        } else {
          open(toggle);
        }
      });
    }
  };

  // Document-level handlers are bound once. ESC closes any open nav; clicking
  // a link inside an open nav closes it so navigation feels predictable.
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    for (const t of document.querySelectorAll(".nav-toggle[aria-expanded='true']")) {
      close(t);
    }
  });

  document.addEventListener("click", (e) => {
    const link = e.target?.closest?.(".site-nav a");
    if (!link) return;
    const nav = link.closest(".site-nav");
    if (nav?.dataset?.navOpen !== "true") return;
    const toggle = document.querySelector(`.nav-toggle[aria-controls="${nav.id}"]`);
    if (toggle) close(toggle, nav);
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => init(document));
  } else {
    init(document);
  }

  // htmx 4 dispatches htmx:after:swap on the *source* element (or, when the swap
  // detached it, on the swapped-in content) rather than on a container enclosing
  // the new nodes, so e.target is not a usable root. Re-scan from the document —
  // the per-node init guards make that a no-op for already-decorated nodes.
  document.addEventListener("htmx:after:swap", () => init(document));
}
