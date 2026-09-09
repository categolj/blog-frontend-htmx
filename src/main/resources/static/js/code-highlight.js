"use strict";
{
  const highlight = (root = document) => {
    if (!window.hljs) return;
    for (const block of root.querySelectorAll(".prose pre code")) {
      if (block.dataset.hljsDone === "1") continue;
      window.hljs.highlightElement(block);
      block.dataset.hljsDone = "1";
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => highlight());
  } else {
    highlight();
  }

  // htmx 4 dispatches htmx:after:swap on the *source* element (or, when the swap
  // detached it, on the swapped-in content) rather than on a container enclosing
  // the new nodes, so e.target is not a usable root. Re-scan from the document —
  // the per-node init guards make that a no-op for already-decorated nodes.
  document.addEventListener("htmx:after:swap", () => highlight());
}
