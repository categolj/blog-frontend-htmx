"use strict";
// Keeps `<html lang>` in step with boosted navigation.
//
// hx-boost swaps <body>'s innerHTML, so the attributes of the response document's
// own <html> element are discarded. Hopping from a Japanese entry to its English
// counterpart (or to the "Not Translated" notice) would leave the page announcing
// itself as Japanese to screen readers, translation prompts and :lang() rules —
// a real navigation to the same URL gets this right, so a boosted one must too.
//
// The language comes from the response's Content-Language header rather than from
// parsing the body: the interceptor in WebConfig sets it from the same model
// attribute the layout renders into `<html lang>`, so the two cannot drift.
{
  // Only full-page swaps carry the language of the page as a whole. A partial
  // (the views counter, an entry-list batch) is rendered by its own controller and
  // reports the site default even while the surrounding page is English — applying
  // that header would flip the page back to Japanese mid-visit.
  document.addEventListener("htmx:before:swap", (e) => {
    const ctx = e.detail?.ctx;
    if (ctx?.target !== document.body) return;
    const lang = ctx.response?.headers?.get("content-language");
    if (lang) document.documentElement.lang = lang;
  });
}
