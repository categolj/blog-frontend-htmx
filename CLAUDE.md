# CLAUDE.md

Guidance for working in this repository. See `README.md` for user-facing usage.

## Architecture overview

- Controllers choose full vs. fragment template based on `Htmx#isPartial`
  (`HX-Request: true` AND `HX-Boosted != true`).
- All views share `layouts/default.mustache` via JMustache's `{{<parent}} … {{/parent}}`
  inheritance.
- `EntryClient` deserialises directly into local records (`Entry`, `FrontMatter`, etc.) —
  those records flatten the API's `@JsonUnwrapped EntryKey` into `entryId` /`tenantId`
  fields.

## Design decisions

### SSR-first with HTMX enhancements

The API returns JSON, but crawlers need rendered HTML. Every page is server-rendered as a
full document; HTMX only replaces parts for interactivity. `hx-boost:inherited` on
`<body>` intercepts link clicks after the first load, so boosted navigation still receives
full HTML (and the server returns it unchanged). Targeted partials (search, Read more) use
`hx-get` on a specific target and the controller returns a fragment template.

### htmx 4 conventions

The site runs htmx 4 (`static/js/vendor/htmx.min.js`), which differs from htmx 2 in three
ways that shape this codebase:

- **Nothing is inherited implicitly.** An attribute meant for descendants needs the
  `:inherited` suffix — hence `<body hx-boost:inherited="true">`. Plain `hx-boost="false"`
  on a descendant still opts that element out.
- **Events are colon-separated and carry a request context.** `htmx:after:swap`,
  `htmx:before:request`, `htmx:response:error`, and `htmx:error` (which absorbs htmx 2's
  sendError / timeout / swapError) are the ones this site listens for; the payload is
  `e.detail.ctx`, not `e.detail.elt` / `e.detail.xhr`.
- **Every response swaps by default.** `<meta name="htmx-config">` in the default layout
  sets `noSwap` back to `[204, 304, "4xx", "5xx"]`: upstream failures arrive as whole
  error documents, and swapping one into a `<span>` (views counter) or the entry list
  would be worse than the toast that `error-toast.js` renders instead. The same meta tag
  carries `defaultTimeout`. A response whose error body *is* the page — the "Not
  Translated" notice served as the 404 of an untranslated EN entry — opts back in with
  `hx-status:404` on the triggering element. The code has to be exact: htmx checks
  `noSwap` before the attribute at each step of its `404` → `40x` → `4xx` lookup, so
  `hx-status:4xx` would lose. `error-toast.js` mirrors that lookup and stays quiet for a
  status the element handles itself.

`Htmx#isPartial` still reads `HX-Request` / `HX-Boosted`, both of which htmx 4 keeps
sending. htmx 4 also offers `HX-Request-Type: full|partial`, but relying on it would break
any client still running a cached htmx 2 bundle.

### Append-style "Read more" instead of Prev/Next

The list uses a single **Read more** button that appends the next batch and replaces
itself with a new button (`hx-target="closest .more-wrap"`, `hx-swap="outerHTML"`). No
Prev button. Rationale: the upstream API is cursor-based and does not maintain page
numbers; infinite append matches the cursor model and keeps the URL stable. The controller
still builds a cumulative URL (`?cursor=…&direction=NEXT`) so browser-back and direct URL
access land on a reasonable starting batch.

### `nextCursor` fallback

The upstream API emits `hasNext: true` but does **not** include `nextCursor` when serving
`findLatest` (the default no-criteria request). `EntryController#effectiveNextCursor`
falls back to the oldest entry's `updated.date` in the current batch. Without this, the
Read more button would never render on the first page.

### Custom `GitHubAlertExtension`

`commonmark-java` has no built-in GitHub-alerts extension at the time of writing, so
blockquotes starting with `[!NOTE|TIP|IMPORTANT|WARNING|CAUTION]` are rendered via a
custom extension as `<div class="markdown-alert markdown-alert-<type>">`. Non-alert
blockquotes render as normal blockquotes.

### Custom `TocExtension`

`<!-- toc -->` placeholders are replaced with a nested `<nav class="toc">` of links to
every heading. The heading ids must be generated with the same
`IdGenerator.builder().build()` config that `HeadingAnchorExtension` uses — if the two
diverge, the TOC links will miss their anchors.

### Page language rides on `Content-Language`

`hx-boost` swaps only `<body>`'s inner HTML, so the response document's own
`<html lang>` is thrown away — after a boosted hop from a Japanese entry to its English
counterpart the page would keep announcing itself as Japanese. `lang-sync.js` mirrors the
response's `Content-Language` onto `<html>`, but only for swaps targeting `<body>`: a
partial is rendered by its own controller and reports the site default even while the
surrounding page is English.

The header is not set directly. `DispatcherServlet` stamps the resolved locale onto the
response with `setLocale` immediately before rendering, which overwrites anything an
interceptor wrote in `postHandle`. So `WebConfig` contributes a `localeResolver` that
resolves from the `htmlLang` the interceptor recorded on the request — one model
attribute driving both `<html lang>` and the header, and `Content-Language` finally
describes the response instead of echoing the client's `Accept-Language`. Handlers that
render no view (raw markdown, RSS, sitemap) fall back to the site's primary language.

### Fonts and look

Site-wide font is serif (`--font-serif`). The article header (breadcrumbs, title, meta) is
styled to match the rest of the page; earlier iterations of the mono/terminal look were
rejected by the user. Keep any new header element within the serif/minimal aesthetic
rather than adding mono or dark pills.

### Cache validation for entry detail pages

The entry detail ETag folds a static-asset-URL digest into the validator
(alongside the entry's update epoch) so that a redeploy with new JS/CSS
content hashes invalidates the cached HTML envelope. Without this, upstream
would keep reporting 304 for the unchanged entry and clients would hold onto
HTML pointing at old fingerprinted asset URLs indefinitely.

### Feature packages must not depend on `am.ik.blog.config`

`config/` wires up beans from feature packages; depending back the other way
would create cycles. Cross-cutting helpers belong in their own sibling package
under `am.ik.blog` — avoid the name `web/`, which would clash with each
feature's own `<feature>.web/` sub-package.

### GraalVM native image runtime hints

Hints live in `am.ik.blog.config.BlogRuntimeHints` (wired via `@ImportRuntimeHints`
on `AppConfig`). The class sits in `config/` because feature packages cannot
depend back on it — see the rule above.

The non-obvious trap: `@ConfigurationProperties` records that are also rendered
by Mustache need explicit `INVOKE_DECLARED_METHODS`. Spring Boot's AOT registers
constructors only (enough for binding), but JMustache reads the accessors
reflectively — so the app boots fine and then throws
`MissingReflectionRegistrationError` the first time a template interpolates one.
Add any such record to `VIEW_MODEL_TYPES`, not just `BINDING_TYPES`.

### Scoped authentication per feature

Auth is a dedicated `SecurityFilterChain` with its own `securityMatcher`, not a rule
carved out of a global chain. Add future protected features the same way — a new
ordered chain — so feature scopes stay isolated.

### JWT lives in a dedicated field on `NoteAuthentication`

`ProviderManager.eraseCredentials()` wipes the inherited `credentials` slot after
successful authentication, so the upstream JWT has to sit in a separate `accessToken`
field to survive into the first outbound `NoteClient` call.

### Testing approach

Application tests stub the upstream Blog API at the HTTP layer rather than mocking
`EntryClient`. `testsupport.MockServer` wraps a JDK `HttpServer` on a random loopback
port; `@DynamicPropertySource` points `blog.api.base-url` at it. This keeps the real
`RestClient` + Jackson decoding path under test, so JSON contract changes from the
upstream API surface in tests instead of at runtime.

Rendered HTML is parsed with Jsoup and asserted on semantic properties (titles, URLs,
attribute values, element counts). Full-body string comparison was tried and rejected:
template/CSS-class churn made every test brittle without catching extra regressions. The
project standard's "assert full output" rule still applies — but at the level of a
selected subtree (e.g. `requireSelected(doc, "article.entry").text()`), not the whole
page chrome.

### Modern client JS

Client scripts target evergreen browsers and are written in ES2020+ directly — `const`/
`let`, arrow functions, optional chaining (`?.`), nullish coalescing (`??`), spread,
`for...of`, `dataset.*`. `closure-compiler` is configured with
`closureLanguageOut=NO_TRANSPILE`, so modern syntax is preserved in the bundled
`app.min.js` rather than down-compiled. When editing existing scripts, stay in this
style — don't regress to `var`, `Array.prototype.slice.call`, `getAttribute("data-*")`,
or `|| ""` fallbacks.

Closure Compiler's own parser stops short of private class fields (`#foo`) at every
optimization level, which is why `vendor/htmx.min.js` is excluded from the bundle and
loaded as its own `<script>`. Any future vendored library written with them has to go the
same way.

### Syntax highlighting via client JS

Highlighting is done by `highlight.js` on the client. GitHub light/dark themes are loaded
with `media="(prefers-color-scheme: …)"` so the browser picks the right one automatically.
Both `/js/code-highlight.js` and `/js/code-copy.js` re-run on `htmx:after:swap` so swapped
content gets the same treatment.

The `.prose pre code.hljs` rule explicitly neutralises the padding, background, and
`overflow` that highlight.js themes add — without it the block ends up with double
scrollbars and extra inner padding.

### External SVG sprite for icons

All icons live in `src/main/resources/static/img/icons.svg` as `<symbol>` definitions
and are referenced as
`<svg class="entry-icon" aria-hidden="true"><use href="{{#src}}/img/icons.svg{{/src}}#folder"/></svg>`.
The sprite is served as a static resource, so
`spring.web.resources.cache.period=365d` + `chain.strategy.content.enabled=true` cache
it for a year and bust it by content hash. Inlining the same `<path>` in every template
(the previous state) repeated bytes on every HTML response and could not be cached.

- The outer `<svg>` carries the CSS class (sizing lives in `style.css`) and
  `aria-hidden="true"`. `viewBox` / `fill` / `stroke-*` belong on the `<symbol>` — they
  scope cleanly to the shadow DOM that `<use>` creates.
- `currentColor` is the only reliable theming hook: page CSS cannot reach paths inside
  the external shadow DOM, but `color` inherits in. Use `stroke="currentColor"` for
  outline icons and `fill="currentColor"` for filled ones.
- `stroke-width` inheritance through external `<use>` is unreliable across browsers, so
  the globe ships as two symbols (`#globe` at 1.8, `#globe-thin` at 1.5) instead of one.

### Header search loading state on `<html>`

The header search form submits via `hx-boost`, which swaps `<body>`'s innerHTML on
response. Two consequences shape `search-indicator.js`:

1. The in-flight flag is mirrored onto `:root[data-searching]` rather than relying on
   the `htmx-request` class htmx adds to the form. The form is detached during the
   body swap, so a class on it would disappear mid-request and the spinner would
   blink out. `<html>` survives the swap, so CSS keyed off `:root[data-searching]`
   stays valid across the old and new forms.
2. The clear listener is bound to `htmx:after:swap`, not `htmx:finally:request`.
   `finally:request` is dispatched on the triggering element (the form), which is
   already detached by the time it fires — the event never bubbles to the
   document-level listener, leaving `data-searching` stuck. htmx re-points
   `after:swap` at the swap target (body) when the source element did not survive
   the swap, so that one always reaches the document.

The listener also toggles `disabled` on the form's inputs during the request to block
re-submits. htmx collects form data before `htmx:before:request` fires, so disabling
there does not drop the submitted query.

## Development workflows

### Run locally

```bash
./mvnw spring-boot:run
```

The app assumes the Blog API is reachable at `blog.api.base-url`. Point it at a real API
(e.g. `https://entry-api.ik.am`) in `application.properties` or set
`BLOG_API_BASE_URL=...` in the environment.

### Apply formatter and run tests

```bash
./mvnw spring-javaformat:apply test
```

`spring-javaformat:apply` must be run before commit — CI will reject unformatted code via
`spring-javaformat:validate`. `nullability-maven-plugin` runs NullAway during `compile`,
so compile failures from `@Nullable` mismatches are real — fix the nullability rather
than suppressing.

### Build and run a native image

```bash
./mvnw -Pnative -DskipTests native:compile
./target/blog-frontend-htmx
```

The `native` profile is inherited from `spring-boot-starter-parent`. When adding
a record that flows through `RestClient` or a Mustache template, update
`BlogRuntimeHints` — see the design-decisions section above.

To smoke-test against the native binary without the real upstream, run a local mock
and point the matching `*_BASE_URL` at it. Spring's `RestClient` sends POST bodies
as `Transfer-Encoding: chunked`, so the mock must read chunks — reading only
`Content-Length` will silently see empty bodies.
