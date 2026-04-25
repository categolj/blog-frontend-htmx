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

  document.body.addEventListener("htmx:afterSwap", (e) => highlight(e.target));
}
