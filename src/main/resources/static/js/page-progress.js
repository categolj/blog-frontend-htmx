"use strict";
// Top progress bar for hx-boost page navigation. Without it, clicking a link
// on a slow connection looks like nothing happened — the browser's native
// loading indicator never appears because htmx intercepts the navigation, so
// the user sits staring at the old page wondering whether the click landed.
//
// State is tracked on :root via data-loading so the indicator survives the
// body innerHTML swap that hx-boost performs on completion. A short delay
// before showing the bar avoids flickering on fast responses.
//
// Scope: only boosted navigations (hx-boost on body covers <a> clicks and
// form submits). Targeted hx-get / hx-post requests like the Read more
// button or the counter have their own local indicators and are excluded
// via e.detail.boosted. The header search form is also excluded because
// search-indicator.js drives its own spinner.
//
// The instant "your click was received" feedback for the clicked link
// itself is handled in CSS via the htmx-request class htmx adds to the
// triggering element — see a.htmx-request in style.css. No JS needed.
{
  const isSearchTrigger = (elt) => elt?.closest?.(".search-form") != null;
  const tracked = new WeakSet();
  let inflight = 0;
  let showTimer = null;
  let doneTimer = null;
  // Matches the longest "done"-phase transition in style.css (opacity 0.2s
  // with 0.12s delay). Holds the data-loading="done" value on <html> long
  // enough for that fill+fade to play out before the attribute is cleared.
  const DONE_HOLD_MS = 320;

  const setLoading = (val) => {
    const html = document.documentElement;
    if (val == null) delete html.dataset.loading;
    else html.dataset.loading = val;
  };

  const start = () => {
    inflight++;
    // Click landed during the previous nav's fade-out: cut the fade and
    // restart cleanly so the bar does not look stale.
    if (doneTimer != null) {
      clearTimeout(doneTimer);
      doneTimer = null;
      setLoading(null);
    }
    if (inflight > 1 || showTimer != null) return;
    showTimer = setTimeout(() => {
      showTimer = null;
      if (inflight > 0) setLoading("1");
    }, 80);
  };

  const stop = () => {
    if (inflight > 0) inflight--;
    if (inflight > 0) return;
    if (showTimer != null) {
      // Fast response — the bar never showed. Drop the timer and exit
      // without playing any animation.
      clearTimeout(showTimer);
      showTimer = null;
      setLoading(null);
      return;
    }
    // Bar is currently visible. Move into the "done" phase so CSS races
    // the width to 100% and then fades the opacity out.
    setLoading("done");
    doneTimer = setTimeout(() => {
      doneTimer = null;
      if (document.documentElement.dataset.loading === "done") setLoading(null);
    }, DONE_HOLD_MS);
  };

  document.addEventListener("htmx:beforeRequest", (e) => {
    const elt = e.detail?.elt;
    const xhr = e.detail?.xhr;
    if (!xhr || !e.detail?.boosted || isSearchTrigger(elt)) return;
    // Track by XHR identity — afterSettle for a boosted nav fires on the
    // swap target (body), not the original anchor (which has been replaced),
    // so the elt is not stable across events. The xhr instance is.
    tracked.add(xhr);
    start();
  });

  const onDone = (e) => {
    const xhr = e.detail?.xhr;
    if (!xhr || !tracked.has(xhr)) return;
    tracked.delete(xhr);
    stop();
  };
  for (const ev of [
    "htmx:afterSettle",
    "htmx:responseError",
    "htmx:sendError",
    "htmx:timeout",
    "htmx:abort",
  ]) {
    document.addEventListener(ev, onDone);
  }
}
