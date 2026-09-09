"use strict";
// Drives :root[data-searching] based on htmx request events from the header
// search form. Tracks state on <html> so it survives the body innerHTML swap
// that hx-boost performs on form submit — a class on the form itself would
// disappear together with the form during the swap.
//
// Clear-side listeners use htmx:after:swap (and error events) rather than
// htmx:finally:request: on a boosted form submit, the form is detached from the
// DOM during the swap, and finally:request is dispatched on that detached
// element, so it never bubbles up to our document-level listener and the spinner
// would stay stuck. htmx re-points after:swap at the swap target when the source
// element did not survive, so that one always reaches the document.
{
  const triggerOf = (e) => e.detail?.ctx?.sourceElement ?? e.target;

  const isSearchTrigger = (e) =>
    triggerOf(e)?.classList?.contains?.("search-form") ?? false;

  document.addEventListener("htmx:before:request", (e) => {
    if (!isSearchTrigger(e)) return;
    document.documentElement.dataset.searching = "1";
    // Disable interactive controls so the user cannot re-submit or mutate the
    // query while the request is in flight. htmx reads form data before this
    // event fires, so disabling here does not drop the submitted query.
    const form = triggerOf(e);
    for (const node of form.querySelectorAll("input, button")) {
      node.disabled = true;
    }
  });

  const clear = () => {
    delete document.documentElement.dataset.searching;
    // Happy path: the form is replaced by the hx-boost swap so the new inputs
    // are fresh. On error paths (no swap) we need to re-enable manually.
    for (const node of document.querySelectorAll(".search-form input, .search-form button")) {
      node.disabled = false;
    }
  };
  for (const ev of ["htmx:after:swap", "htmx:response:error", "htmx:error"]) {
    document.addEventListener(ev, clear);
  }
}
