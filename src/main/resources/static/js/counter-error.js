"use strict";
// Swaps the views-counter element to a silent error state (red "!" icon) when
// its htmx request fails. error-toast.js filters these same events out so no
// global toast fires — the inline indicator is the only signal the user sees.
{
  const isCounter = (el) => el?.classList?.contains("views-counter") ?? false;

  const markError = (el) => {
    el.classList.add("views-error");
    const count = el.querySelector(".views-count");
    if (count) count.innerHTML = "";
    el.setAttribute("title", "Failed to load views");
  };

  const handle = (e) => {
    if (isCounter(e.target)) markError(e.target);
  };

  for (const name of ["htmx:responseError", "htmx:sendError", "htmx:swapError", "htmx:timeout"]) {
    document.body.addEventListener(name, handle);
  }
}
