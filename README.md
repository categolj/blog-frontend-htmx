# blog-frontend-htmx

A server-rendered blog frontend for the [entry-api](https://github.com/making/entry-api) Blog
API. Built with Spring Boot, Mustache, and HTMX.

## When to use this

Use this service when you want a public-facing reader UI for the Blog API with:

- Full HTML on first request (so search crawlers see the article body)
- HTMX-powered partial updates for search and pagination
- Markdown rendering with GitHub-flavored extensions and alert callouts
- Syntax highlighting and copy buttons for code blocks

The admin/editor UI is out of scope — authenticated write paths
(`POST`, `PUT`, `DELETE`, `PATCH`, `/s3/presign`, `/summary`) are not implemented here.

## Requirements

- Java 25
- A reachable Blog API (e.g. `https://entry-api.ik.am` or a local instance on port 8080)

## Running

```bash
./mvnw spring-boot:run
```

By default the frontend listens on `http://localhost:8081` and calls the API at
`http://localhost:8080`. Override the API URL via `application.properties` or environment
variable:

```properties
blog.api.base-url=https://entry-api.ik.am
```

```bash
BLOG_API_BASE_URL=https://entry-api.ik.am ./mvnw spring-boot:run
```

## Pages

| Path                 | Purpose                                                        |
|----------------------|----------------------------------------------------------------|
| `/`                  | Latest entries (alias of `/entries`)                           |
| `/entries`           | Entry list with search, tag/category filter, cursor pagination |
| `/entries/{id}`      | Entry detail rendered from markdown                            |
| `/tags`              | Tag cloud with usage counts                                    |
| `/categories`        | Flat list of category paths                                    |

Query parameters on `/entries`:

- `query` — full-text search passed through to the API
- `tag` — filter by tag name
- `categories` — comma-separated category path
- `cursor` — ISO-8601 instant from a previous response
- `direction` — `NEXT` or `PREVIOUS`

## HTMX behaviour

- `<body hx-boost="true">` turns internal navigation into background fetches while still
  serving full HTML for direct requests and crawlers.
- The search input re-queries the server on input change and swaps only the `#entries`
  section.
- The **Read more** button at the bottom of the list fetches the next cursor page and
  appends the new batch in place of the button.

All three paths hit the same controller. The fragment vs. full page decision is based on
the `HX-Request` / `HX-Boosted` headers; see `Htmx#isPartial`.

## Markdown rendering

Server-side via `commonmark-java`. Enabled extensions:

- GFM tables, strikethrough, task list items
- Autolinks, heading anchor ids
- GitHub-style alerts — blockquotes starting with `[!NOTE]`, `[!TIP]`, `[!IMPORTANT]`,
  `[!WARNING]`, or `[!CAUTION]` are promoted to styled callouts
- `<!-- toc -->` is replaced with a nested list of links to every heading in the document

Client-side add-ons (no server dependency):

- `highlight.js` for syntax highlighting (GitHub light/dark themes via
  `prefers-color-scheme`)
- A copy button in the top-right corner of every `<pre>` block

Both re-run after HTMX swaps via the `htmx:afterSwap` event.

## Configuration

| Property             | Default                 | Description                    |
|----------------------|-------------------------|--------------------------------|
| `server.port`        | `8081`                  | HTTP port                      |
| `blog.api.base-url`  | `http://localhost:8080` | Upstream Blog API              |

## Build and test

```bash
./mvnw spring-javaformat:apply test
./mvnw clean package
```

Tests use `@SpringBootTest` with `RestTestClient` and mock the `EntryClient` via
`@MockitoBean` — no running Blog API is required.
