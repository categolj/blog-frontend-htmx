"use strict";
{
  // Strip a leading shell prompt ("$") so pasting yields just the command.
  // Keeps backslash-continued lines together and drops any trailing output.
  const stripShellPrompt = (text) => {
    if (!text.startsWith("$")) return text;
    const lines = text.slice(1).trimStart().split("\n");
    const kept = [];
    for (const raw of lines) {
      const line = raw.trimEnd();
      kept.push(line);
      if (!line.endsWith("\\")) break;
    }
    return kept.join("\n");
  };

  const attach = (pre) => {
    if (pre.dataset.copyInit === "1") return;
    pre.dataset.copyInit = "1";

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy-btn";
    btn.setAttribute("aria-label", "Copy code to clipboard");
    btn.textContent = "Copy";

    btn.addEventListener("click", async () => {
      const code = pre.querySelector("code");
      const raw = code?.textContent ?? pre.textContent ?? "";
      const text = stripShellPrompt(raw);
      try {
        if (navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(text);
        } else {
          const ta = document.createElement("textarea");
          ta.value = text;
          ta.style.position = "fixed";
          ta.style.opacity = "0";
          document.body.appendChild(ta);
          ta.select();
          document.execCommand("copy");
          ta.remove();
        }
        btn.textContent = "Copied";
        btn.classList.add("is-copied");
      } catch {
        btn.textContent = "Failed";
      }
      setTimeout(() => {
        btn.textContent = "Copy";
        btn.classList.remove("is-copied");
      }, 1500);
    });

    pre.appendChild(btn);
  };

  const init = (root = document) => {
    root.querySelectorAll(".prose pre").forEach(attach);
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => init());
  } else {
    init();
  }

  document.body.addEventListener("htmx:afterSwap", (e) => init(e.target));
}
