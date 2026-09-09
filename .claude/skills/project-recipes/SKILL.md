---
name: project-recipes
description: Step-by-step recipes for adding things to this repository - a new page (controller + Mustache template + test), a new markdown extension, a new SVG sprite icon, or a new client-side script. Use when creating any of those.
---

# Adding things to this repository

The design rationale behind these constraints lives in `CLAUDE.md`'s
"Design decisions" section - read that for *why*; this file is the *how*.

### Add a new page

1. Create the controller under the feature package, e.g.
   `am.ik.blog.<feature>.web.<Feature>Controller`.
2. Return a Mustache template path; include `{{<layouts/default}} … {{/layouts/default}}`
   for full pages.
3. If the page needs HTMX partial swaps, branch on `Htmx#isPartial` and return a
   fragment template from `templates/fragments/`.
4. Add a `@SpringBootTest` that drives the page via `RestTestClient`. Mock the upstream
   Blog API at the HTTP layer with `testsupport.MockServer` (see "Testing approach"
   in `CLAUDE.md`) — do not mock `EntryClient` itself.

### Add a new markdown feature

Add the extension to `MarkdownRenderer`'s constructor. For features that need to observe
the full document (like TOC), use `Parser.ParserExtension` with a `PostProcessor`. For
pure rendering changes (like the alert callouts), use `HtmlRenderer.HtmlRendererExtension`
with a custom `NodeRenderer`. Ensure the test in `MarkdownRendererTests` uses
`isEqualToNormalizingWhitespace` on a text block — the project standard forbids
`contains` for output assertions.

### Add a new icon

1. Add a `<symbol id="…">` to `src/main/resources/static/img/icons.svg` with the
   icon's `viewBox` and its `fill` / `stroke*` presentation attributes. Use
   `currentColor` for whatever colour the host CSS should control.
2. Reference it from a template as
   `<svg class="…" aria-hidden="true"><use href="{{#src}}/img/icons.svg{{/src}}#id"/></svg>`.
   The class on the outer `<svg>` controls sizing via `style.css`.
3. Do not inline raw `<path>` in templates — see "External SVG sprite for icons" in `CLAUDE.md` for
   the constraints (shadow-DOM scoping, `stroke-width` caveat).

### Add a new client-side script

Scripts under `src/main/resources/static/js/` are concatenated into `app.min.js` by
`closure-compiler-maven-plugin` at build time. **New files must be added to the
`<includes>` list in `pom.xml`** — order matters (globals-providing libraries first,
their consumers after). Third-party vendored libraries live under
`src/main/resources/static/js/vendor/` (e.g. `vendor/htmx.min.js`,
`vendor/highlight.min.js`); user-authored scripts sit directly under `static/js/`.
`vendor/htmx.min.js` is the exception to the bundle — see "Modern client JS" in `CLAUDE.md`.
`ClientScriptBundleTest` fails if a script is missing from either list, or if the two
disagree on order; a script that must stay out of the bundle is declared, with its
reason, in that test's `NOT_BUNDLED` map.

Follow the convention used by the existing files:

1. Wrap the body in a block scope (`{ ... }`), not an IIFE — `let`/`const` already scope.
2. Define an `init(root)` (or similarly named) function that decorates nodes under
   `root`, gated by a `dataset.*Init` flag on each decorated node so re-entry is a no-op.
3. Run init on `DOMContentLoaded` (or immediately if the document is already loaded)
   *and* on `document`'s `htmx:after:swap` event, re-scanning from `document`. HTMX
   partial swaps inject fresh DOM that the initial pass never saw, so re-scanning is
   necessary — but the scan will also revisit already-initialised nodes, and those must
   be skipped. Do not scope the rescan to `e.target`: htmx 4 dispatches `after:swap` on
   the source element, or on the swapped-in content when the swap detached it, so the
   event target is not a container enclosing the new nodes.
