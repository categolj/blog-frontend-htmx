"use strict";
{
  const init = (root) => {
    const list = root.querySelector("#tag-list");
    if (!list || list.dataset.tagFilterReady === "1") return;
    list.dataset.tagFilterReady = "1";

    const input = root.querySelector("#tag-filter-input");
    const empty = root.querySelector("#tag-list-empty");
    const buttons = [...root.querySelectorAll(".tag-sort-btn")];
    const items = [...list.querySelectorAll(".tag-list-item")];
    const state = { sort: "name", filter: "" };

    const byName = (a, b) =>
      (a.dataset.name ?? "").localeCompare(b.dataset.name ?? "", undefined, {
        sensitivity: "base",
      });

    const applyFilter = () => {
      const q = state.filter.trim().toLowerCase();
      let visible = 0;
      for (const item of items) {
        const name = (item.dataset.name ?? "").toLowerCase();
        const match = q === "" || name.includes(q);
        item.hidden = !match;
        if (match) visible++;
      }
      if (empty) empty.hidden = visible !== 0;
    };

    const applySort = () => {
      const sorted = [...items];
      if (state.sort === "count") {
        sorted.sort((a, b) => {
          const ca = Number.parseInt(a.dataset.count, 10) || 0;
          const cb = Number.parseInt(b.dataset.count, 10) || 0;
          return cb !== ca ? cb - ca : byName(a, b);
        });
      } else {
        sorted.sort(byName);
      }
      const frag = document.createDocumentFragment();
      for (const el of sorted) frag.appendChild(el);
      list.appendChild(frag);
    };

    for (const btn of buttons) {
      btn.addEventListener("click", (e) => {
        const current = e.currentTarget;
        const sort = current.dataset.sort;
        if (!sort || sort === state.sort) return;
        state.sort = sort;
        for (const b of buttons) {
          const active = b === current;
          b.classList.toggle("is-active", active);
          b.setAttribute("aria-pressed", active ? "true" : "false");
        }
        applySort();
      });
    }

    input?.addEventListener("input", () => {
      state.filter = input.value;
      applyFilter();
    });
  };

  const bootstrap = (root = document) => {
    const el = root.querySelector?.("[data-tag-page]");
    if (el) {
      init(el);
    } else if (root.matches?.("[data-tag-page]")) {
      init(root);
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => bootstrap());
  } else {
    bootstrap();
  }

  // htmx 4 dispatches htmx:after:swap on the *source* element (or, when the swap
  // detached it, on the swapped-in content) rather than on a container enclosing
  // the new nodes, so e.target is not a usable root. Re-scan from the document —
  // the per-node init guards make that a no-op for already-decorated nodes.
  document.addEventListener("htmx:after:swap", () => bootstrap());
}
