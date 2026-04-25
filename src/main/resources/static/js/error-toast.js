"use strict";
// Surfaces htmx transport failures as a global toast so users notice when the
// server is unreachable or returns an error after the initial page load.
// Without this, htmx swallows failures silently — existing indicators (search
// spinner, header particles) clear, but no message is shown.
//
// Listeners are bound to document so hx-boost navigation and all hx-*
// requests are covered. The toast element is injected lazily on the first
// error so the idle DOM stays clean.
{
  // htmx has no default request timeout, so a process that accepts TCP but
  // never responds would hang forever. 15s is long enough for cold upstream
  // API calls while still surfacing as htmx:timeout within a reasonable bound.
  if (typeof htmx !== "undefined") {
    htmx.config.timeout = 15000;
  }

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

  document.addEventListener("htmx:sendError", (e) => {
    if (isSilent(e)) return;
    show("Can't reach the server. Please try again in a moment.");
  });

  document.addEventListener("htmx:responseError", (e) => {
    if (isSilent(e)) return;
    const status = e.detail?.xhr?.status ?? 0;
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

  document.addEventListener("htmx:timeout", (e) => {
    if (isSilent(e)) return;
    show("The server didn't respond in time. Please try again.");
  });

  document.addEventListener("htmx:swapError", (e) => {
    if (isSilent(e)) return;
    show("Failed to update the page.");
  });
}
