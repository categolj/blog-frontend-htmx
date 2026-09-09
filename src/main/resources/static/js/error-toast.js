"use strict";
// Surfaces htmx transport failures as a global toast so users notice when the
// server is unreachable or returns an error after the initial page load.
// Without this, htmx swallows failures silently — existing indicators (search
// spinner, header particles) clear, but no message is shown.
//
// Listeners are bound to document so hx-boost navigation and all hx-*
// requests are covered. The toast element is injected lazily on the first
// error so the idle DOM stays clean.
//
// The request timeout that produces the "didn't respond in time" case is set
// as htmx.config.defaultTimeout in the <meta name="htmx-config"> of the
// default layout.
{
  const HIDE_MS = 8000;
  let toast = null;
  let hideTimer = null;
  let lastMessage = null;

  const scheduleHide = () => {
    if (hideTimer != null) clearTimeout(hideTimer);
    hideTimer = setTimeout(hide, HIDE_MS);
  };

  const hide = () => {
    if (hideTimer != null) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }
    if (toast) toast.hidden = true;
    lastMessage = null;
  };

  const ensureToast = () => {
    // hx-boost replaces body.innerHTML on navigation, which detaches a
    // previously-inserted toast. Rebuild when that happens.
    if (toast?.isConnected) return toast;
    toast = document.createElement("div");
    toast.className = "error-toast";
    toast.setAttribute("role", "status");
    toast.setAttribute("aria-live", "polite");
    toast.setAttribute("aria-atomic", "true");
    toast.hidden = true;

    const msg = document.createElement("p");
    msg.className = "error-toast-msg";
    toast.appendChild(msg);

    const close = document.createElement("button");
    close.type = "button";
    close.className = "error-toast-close";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";
    close.addEventListener("click", hide);
    toast.appendChild(close);

    toast.addEventListener("mouseenter", () => {
      if (hideTimer != null) {
        clearTimeout(hideTimer);
        hideTimer = null;
      }
    });
    toast.addEventListener("mouseleave", scheduleHide);

    document.body.appendChild(toast);
    return toast;
  };

  const show = (message) => {
    if (message === lastMessage && toast?.isConnected && !toast.hidden) {
      scheduleHide();
      return;
    }
    const el = ensureToast();
    el.querySelector(".error-toast-msg").textContent = message;
    el.hidden = false;
    lastMessage = message;
    scheduleHide();
  };

  // Elements that opt out of global error surfacing — counter-error.js
  // handles its own silent recovery, so a toast would be noise.
  const isSilent = (e) => e.target?.classList?.contains("views-counter") ?? false;

  // htmx 4 folds sendError / timeout / swapError into a single htmx:error. The
  // three cases are still distinguishable from the detail: a request that ran past
  // htmx.config.defaultTimeout is aborted, surfacing as an AbortError (nothing else
  // in this app aborts requests — the default "queue first" sync strategy never
  // does), and a failure raised after a response arrived is a swap failure.
  const failureMessage = (detail) => {
    if (detail?.error?.name === "AbortError") {
      return "The server didn't respond in time. Please try again.";
    }
    if (detail?.ctx?.response) {
      return "Failed to update the page.";
    }
    return "Can't reach the server. Please try again in a moment.";
  };

  document.addEventListener("htmx:error", (e) => {
    if (isSilent(e)) return;
    show(failureMessage(e.detail));
  });

  document.addEventListener("htmx:response:error", (e) => {
    if (isSilent(e)) return;
    const status = e.detail?.ctx?.response?.status ?? 0;
    if (status >= 500) {
      show(`The server returned an error (${status}). Please try again later.`);
    }
    else if (status >= 400) {
      show(`Request was rejected (${status}).`);
    }
    else {
      show("The request failed.");
    }
  });
}
