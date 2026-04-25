"use strict";
// Drives :root[data-searching] based on htmx request events from the header
// search form. Tracks state on <html> so it survives the body innerHTML swap
// that hx-boost performs on form submit — a class on the form itself would
// disappear together with the form during the swap.
//
// Clear-side listeners use htmx:afterSettle (and error events) rather than
// htmx:afterRequest: on a boosted form submit, the form is detached from the
// DOM during the swap, so afterRequest — dispatched on that detached element —
// never bubbles up to our document-level listener, leaving the spinner stuck.
{
  const isSearchTrigger = (e) => {
    const elt = e.detail?.elt ?? e.target;
    return elt?.classList?.contains?.("search-form") ?? false;
  };

  document.addEventListener("htmx:beforeRequest", (e) => {
    if (!isSearchTrigger(e)) return;
    document.documentElement.dataset.searching = "1";
    // Disable interactive controls so the user cannot re-submit or mutate the
    // query while the request is in flight. htmx reads form data before this
    // event fires, so disabling here does not drop the submitted query.
    const form = e.detail?.elt ?? e.target;
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
  for (const ev of ["htmx:afterSettle", "htmx:responseError", "htmx:sendError", "htmx:timeout"]) {
    document.addEventListener(ev, clear);
  }
}
