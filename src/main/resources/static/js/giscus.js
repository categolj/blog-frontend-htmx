"use strict";
{
  // Origin the Giscus iframe is hosted on; postMessage targets must match exactly.
  const GISCUS_ORIGIN = "https://giscus.app";

  // Map the site's three-state toggle (system / light / dark) to one of the Giscus
  // theme keywords. The inline script in layouts/default.mustache has already set
  // <html data-theme> from localStorage by the time this init runs, so reading that
  // attribute gives the same answer the rest of the site uses.
  const resolveTheme = () => {
    const t = document.documentElement.dataset.theme;
    return t === "light" || t === "dark" ? t : "preferred_color_scheme";
  };

  // Push the current theme into every live Giscus iframe. Sent on any <html
  // data-theme> change (including attribute removal when the toggle cycles back to
  // "system"). If the iframe hasn't mounted yet, the message is a harmless no-op —
  // the initial theme came in via the script's data-theme at creation time.
  const syncTheme = () => {
    const iframes = document.querySelectorAll("iframe.giscus-frame");
    if (iframes.length === 0) return;
    const payload = { giscus: { setConfig: { theme: resolveTheme() } } };
    for (const iframe of iframes) {
      iframe.contentWindow?.postMessage(payload, GISCUS_ORIGIN);
    }
  };

  // Attach the <html data-theme> observer lazily — only once we have actually
  // embedded a Giscus section on a page. Because this file is bundled into
  // app.min.js and loaded site-wide, unconditionally attaching the observer would
  // fire syncTheme() on every theme toggle across the whole site, even on pages
  // (tags, categories, about, etc.) that never render Giscus. The flag persists on
  // `document` so boost navigations between entry pages don't stack observers.
  const attachThemeObserver = () => {
    if (document.__giscusThemeObserver) return;
    document.__giscusThemeObserver = new MutationObserver(syncTheme);
    document.__giscusThemeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"],
    });
  };

  // Creating the <script> from JS (rather than emitting it server-side) lets us set
  // data-theme to the already-resolved site theme up-front, avoiding a light→dark
  // flash on first paint when the user has manually selected dark.
  const init = (root) => {
    const sections = root.querySelectorAll?.("section.comments[data-giscus-repo]") ?? [];
    if (sections.length === 0) return;
    attachThemeObserver();
    for (const section of sections) {
      if (section.dataset.giscusInit) continue;
      section.dataset.giscusInit = "1";
      const ds = section.dataset;
      const script = document.createElement("script");
      script.src = `${GISCUS_ORIGIN}/client.js`;
      script.async = true;
      script.crossOrigin = "anonymous";
      script.dataset.repo = ds.giscusRepo;
      script.dataset.repoId = ds.giscusRepoId;
      script.dataset.category = ds.giscusCategory;
      script.dataset.categoryId = ds.giscusCategoryId;
      script.dataset.mapping = ds.giscusMapping;
      script.dataset.reactionsEnabled = "1";
      script.dataset.emitMetadata = "0";
      script.dataset.inputPosition = "bottom";
      script.dataset.theme = resolveTheme();
      script.dataset.lang = "en";
      section.appendChild(script);
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => init(document));
  }
  else {
    init(document);
  }
  document.addEventListener("htmx:afterSwap", (e) => init(e.target));
}
