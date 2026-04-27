package am.ik.blog.entry.web;

import am.ik.blog.BlogProps;
import am.ik.blog.asset.AssetsVersion;
import am.ik.blog.entry.Category;
import am.ik.blog.entry.CursorPage;
import am.ik.blog.entry.Entry;
import am.ik.blog.entry.EntryClient;
import am.ik.blog.entry.EntryQuery;
import am.ik.blog.entry.EntryQuery.PageDirection;
import am.ik.blog.entry.GiscusProps;
import am.ik.blog.htmx.Htmx;
import am.ik.blog.markdown.MarkdownRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class EntryController {

	/**
	 * Threshold (in calendar years) past which the entry-detail page renders the "may be
	 * out of date" banner. Matches the React frontend's 2-year window.
	 */
	private static final int STALE_YEARS_THRESHOLD = 2;

	/**
	 * Upstream tenant identifier for the English-language site. The React frontend uses
	 * the same literal value — it maps to the {@code ik.am_en} repository on the upstream
	 * Blog API (served under {@code /tenants/en/entries…}).
	 */
	private static final String EN_TENANT_ID = "en";

	/**
	 * SWR-style cache policy for entry detail pages: let the browser cache for up to an
	 * hour, and continue serving from cache for another 10 minutes while revalidating in
	 * the background. Matches the React frontend ({@code blog-frontend}'s
	 * {@code EntryController#swrCacheControl}).
	 */
	private static final CacheControl ENTRY_CACHE_CONTROL = CacheControl.maxAge(Duration.ofHours(1))
		.staleWhileRevalidate(Duration.ofMinutes(10));

	/**
	 * Shorter policy for list-style pages (home, tag, category landing). New entries land
	 * at the top often enough that we prefer revalidation within a minute.
	 */
	static final CacheControl LIST_CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(1))
		.staleWhileRevalidate(Duration.ofMinutes(5));

	/**
	 * Full and partial swaps return different HTML for the same URL, so the browser (and
	 * any shared cache) must key by HTMX headers. We only emit cache headers on
	 * non-partial responses, but the {@code Vary} advertisement keeps caches honest.
	 */
	private static final String CACHE_VARY = "HX-Request, HX-Boosted";

	private final EntryClient entryClient;

	private final MarkdownRenderer markdownRenderer;

	private final BlogProps blogProps;

	private final GiscusProps giscusProps;

	private final InstantSource instantSource;

	private final AssetsVersion assetsVersion;

	public EntryController(EntryClient entryClient, MarkdownRenderer markdownRenderer, BlogProps blogProps,
			GiscusProps giscusProps, InstantSource instantSource, AssetsVersion assetsVersion) {
		this.entryClient = entryClient;
		this.markdownRenderer = markdownRenderer;
		this.blogProps = blogProps;
		this.giscusProps = giscusProps;
		this.instantSource = instantSource;
		this.assetsVersion = assetsVersion;
	}

	@GetMapping({ "/", "/entries" })
	public String index(@RequestParam(name = "query", required = false) @Nullable String query,
			@RequestParam(name = "cursor", required = false) @Nullable String cursor,
			@RequestParam(name = "direction", required = false) @Nullable String direction, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		return renderEntryList(query, null, null, cursor, direction, null, model, request, response);
	}

	/**
	 * English entry list at {@code /entries/en}. Mirrors {@link #index} but pulls from
	 * the {@code en} upstream tenant. The React frontend serves the same URL, so existing
	 * bookmarks and indexing keep working.
	 */
	@GetMapping("/entries/en")
	public String indexEn(@RequestParam(name = "query", required = false) @Nullable String query,
			@RequestParam(name = "cursor", required = false) @Nullable String cursor,
			@RequestParam(name = "direction", required = false) @Nullable String direction, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		return renderEntryList(query, null, null, cursor, direction, EN_TENANT_ID, model, request, response);
	}

	/**
	 * Tag landing URL: {@code /tags/{tag}/entries}. Matches the React frontend so
	 * bookmarks and external links keep working after the migration. The legacy
	 * {@code /entries?tag=…} shape is removed — the path is now the single source of
	 * truth.
	 *
	 * <p>
	 * {@link URLDecoder#decode} is applied explicitly because Spring's PathPatternParser
	 * keeps {@code %xx} sequences literal in the path-variable value (so the sitemap's
	 * {@code Spring%20Boot} would otherwise reach the filter as-is).
	 */
	@GetMapping("/tags/{tag}/entries")
	public String tagEntries(@PathVariable("tag") String tag,
			@RequestParam(name = "query", required = false) @Nullable String query,
			@RequestParam(name = "cursor", required = false) @Nullable String cursor,
			@RequestParam(name = "direction", required = false) @Nullable String direction, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		String decodedTag = URLDecoder.decode(tag, StandardCharsets.UTF_8).trim();
		if (decodedTag.isEmpty()) {
			return renderNotFound(request, response, model);
		}
		return renderEntryList(query, decodedTag, null, cursor, direction, null, model, request, response);
	}

	/**
	 * Category landing URL: {@code /categories/programming,java/entries}. The chain is a
	 * single path segment with comma-joined names (matches the React frontend), so the
	 * route plays nicely with
	 * {@link org.springframework.web.util.pattern.PathPatternParser} without any
	 * {@code **} wildcard in the middle of the pattern.
	 */
	@GetMapping("/categories/{categories}/entries")
	public String categoryEntries(@PathVariable("categories") String categories,
			@RequestParam(name = "query", required = false) @Nullable String query,
			@RequestParam(name = "cursor", required = false) @Nullable String cursor,
			@RequestParam(name = "direction", required = false) @Nullable String direction, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		List<String> categoryChain = CategoryUrl.parse(categories);
		if (categoryChain == null) {
			return renderNotFound(request, response, model);
		}
		return renderEntryList(query, null, categoryChain, cursor, direction, null, model, request, response);
	}

	private String renderEntryList(@Nullable String query, @Nullable String tag, @Nullable List<String> categoryList,
			@Nullable String cursor, @Nullable String direction, @Nullable String tenantId, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		PageDirection pageDirection = parseDirection(direction);
		EntryQuery entryQuery = EntryQuery.builder()
			.query(query)
			.tag(tag)
			.categories(categoryList)
			.cursor(cursor)
			.direction(pageDirection)
			.size(EntryQuery.DEFAULT_SIZE)
			.build();
		CursorPage<Entry> page = this.entryClient.findEntries(entryQuery, tenantId);
		String basePath = basePath(request);
		List<CategoryCrumb> categoryCrumbs = (categoryList == null || categoryList.isEmpty()) ? List.of()
				: CategoryCrumb.fromChain(categoryList.stream().map(Category::new).toList());
		model.addAttribute("page", page);
		model.addAttribute("query", query);
		model.addAttribute("tag", tag);
		model.addAttribute("categoryCrumbs", categoryCrumbs);
		model.addAttribute("hasCategoryCrumbs", !categoryCrumbs.isEmpty());
		model.addAttribute("hasFilter", StringUtils.hasText(query) || StringUtils.hasText(tag)
				|| (categoryList != null && !categoryList.isEmpty()));
		model.addAttribute("pageTitle", buildPageTitle(query, tag, categoryList));
		if (EN_TENANT_ID.equals(tenantId)) {
			model.addAttribute("htmlLang", "en");
		}
		String nextCursor = effectiveNextCursor(page);
		// Tag and category always live in basePath (hierarchical URLs), never as query
		// params — so cursor/direction/query are the only params to append.
		String nextUrl = buildPageUrl(basePath, nextCursor, PageDirection.NEXT, query);
		model.addAttribute("nextUrl", nextUrl);
		if (Htmx.isPartial(request)) {
			// When loading more via the Load more button (cursor is present) return an
			// unwrapped fragment that is appended after the current batch. Otherwise
			// (search / filter / initial HTMX swap) return the wrapped #entries section.
			if (StringUtils.hasText(cursor)) {
				return "fragments/entry-list-append";
			}
			return "fragments/entry-list";
		}
		response.setHeader(HttpHeaders.CACHE_CONTROL, LIST_CACHE_CONTROL.getHeaderValue());
		response.setHeader(HttpHeaders.VARY, CACHE_VARY);
		return "index";
	}

	@GetMapping("/entries/{entryId:\\d+}")
	public Object detail(@PathVariable Long entryId,
			@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatchHeader,
			@RequestHeader(name = HttpHeaders.IF_MODIFIED_SINCE,
					required = false) @Nullable String ifModifiedSinceHeader,
			HttpServletResponse response, Model model, HttpServletRequest request) {
		boolean fullPage = !Htmx.isPartial(request);
		if (fullPage) {
			ResponseEntity<Void> notModified = evaluateConditional(entryId, null, ifNoneMatchHeader,
					ifModifiedSinceHeader);
			if (notModified != null) {
				return notModified;
			}
		}
		Optional<Entry> found = this.entryClient.findById(entryId);
		if (found.isEmpty()) {
			return renderNotFound(request, response, model);
		}
		Entry entry = found.get();
		if (fullPage) {
			applyEntryCacheHeaders(response, entry);
		}
		return renderEntry(entry, null, model, request);
	}

	/**
	 * English entry detail at {@code /entries/{id}/en}. Loads the entry from the
	 * {@code en} upstream tenant and localizes the page metadata.
	 *
	 * <p>
	 * When the English tenant has no translation for the requested id but the Japanese
	 * version exists, render a "not translated yet" notice instead of a bare 404 so
	 * readers can hop to the original article. Only when neither tenant has the entry do
	 * we return 404.
	 */
	@GetMapping("/entries/{entryId:\\d+}/en")
	public Object detailEn(@PathVariable Long entryId,
			@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) @Nullable String ifNoneMatchHeader,
			@RequestHeader(name = HttpHeaders.IF_MODIFIED_SINCE,
					required = false) @Nullable String ifModifiedSinceHeader,
			HttpServletResponse response, Model model, HttpServletRequest request) {
		boolean fullPage = !Htmx.isPartial(request);
		// Only short-circuit when the EN tenant itself returns 304. Any other status
		// (including 404 for "no translation yet") falls through to the normal path so
		// the JA fallback / not-translated notice can render as before.
		if (fullPage) {
			ResponseEntity<Void> notModified = evaluateConditional(entryId, EN_TENANT_ID, ifNoneMatchHeader,
					ifModifiedSinceHeader);
			if (notModified != null) {
				return notModified;
			}
		}
		Optional<Entry> en = this.entryClient.findById(entryId, EN_TENANT_ID);
		if (en.isPresent()) {
			if (fullPage) {
				applyEntryCacheHeaders(response, en.get());
			}
			return renderEntry(en.get(), EN_TENANT_ID, model, request);
		}
		Optional<Entry> ja = this.entryClient.findById(entryId);
		if (ja.isPresent()) {
			// Not-translated notice is not keyed to a stable Last-Modified (becomes a
			// real article once translation lands), so skip caching on this branch.
			return renderNotTranslated(entryId, ja.get(), model, request);
		}
		return renderNotFound(request, response, model);
	}

	private String renderEntry(Entry entry, @Nullable String tenantId, Model model, HttpServletRequest request) {
		boolean isEn = EN_TENANT_ID.equals(tenantId);
		model.addAttribute("entry", entry);
		model.addAttribute("contentHtml", this.markdownRenderer.render(entry.content()));
		model.addAttribute("pageTitle", entry.title());
		long staleYears = yearsSinceUpdate(effectiveLastTouched(entry), this.instantSource.instant());
		model.addAttribute("isStale", staleYears >= STALE_YEARS_THRESHOLD);
		model.addAttribute("staleYears", staleYears);
		List<CategoryCrumb> crumbs = CategoryCrumb.fromChain(entry.frontMatter().categories());
		model.addAttribute("categoryCrumbs", crumbs);
		model.addAttribute("hasCategoryCrumbs", !crumbs.isEmpty());
		String entryPath = isEn ? "/entries/" + entry.entryId() + "/en" : "/entries/" + entry.entryId();
		model.addAttribute("markdownUrl", entryPath + ".md");
		// Toggle switches between the current entry's JA and EN counterparts. Shown
		// next to the MD badge in the article header.
		model.addAttribute("languageToggle", isEn ? new LanguageToggle.Link("JA", "/entries/" + entry.entryId())
				: new LanguageToggle.Link("EN", "/entries/" + entry.entryId() + "/en"));
		String canonicalUrl = this.blogProps.baseUrl() + entryPath;
		model.addAttribute("canonicalUrl", canonicalUrl);
		model.addAttribute("ogImage", this.blogProps.defaultOgImage());
		model.addAttribute("ogType", "article");
		if (isEn) {
			model.addAttribute("htmlLang", "en");
		}
		addShareUrls(model, canonicalUrl, entry.title());
		model.addAttribute("giscus", this.giscusProps);
		if (Htmx.isPartial(request)) {
			return "fragments/entry-detail";
		}
		return "entry-detail";
	}

	/**
	 * Renders the "not translated yet" notice served in place of an English entry that
	 * does not exist in the {@code en} tenant. Links to the Japanese original and to a
	 * prefilled GitHub issue for requesting the translation (mirrors the React
	 * {@code NotTranslated} component).
	 */
	private String renderNotTranslated(Long entryId, Entry jaEntry, Model model, HttpServletRequest request) {
		String jaUrl = "/entries/" + entryId;
		model.addAttribute("entryId", entryId);
		model.addAttribute("jaUrl", jaUrl);
		model.addAttribute("jaTitle", jaEntry.title());
		model.addAttribute("issueUrl", buildTranslationIssueUrl(entryId));
		model.addAttribute("pageTitle", "Not translated yet");
		model.addAttribute("htmlLang", "en");
		String canonicalUrl = this.blogProps.baseUrl() + "/entries/" + entryId + "/en";
		model.addAttribute("canonicalUrl", canonicalUrl);
		if (Htmx.isPartial(request)) {
			return "fragments/not-translated";
		}
		return "not-translated";
	}

	/**
	 * Builds the "file a translation request" GitHub link. Matches the React
	 * {@code NotTranslated} component's URL shape so an existing issue template — keyed
	 * off the title prefix — keeps working after the migration.
	 */
	private static String buildTranslationIssueUrl(Long entryId) {
		return UriComponentsBuilder.fromUriString("https://github.com/making/ik.am_en/issues/new")
			.queryParam("title", "Translation Request to " + entryId)
			.queryParam("body", "Please translate https://ik.am/entries/" + entryId + " into English")
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();
	}

	@GetMapping(path = "/entries/{entryId:\\d+}.md", produces = "text/markdown;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<String> markdown(@PathVariable Long entryId) {
		return this.entryClient.findMarkdownById(entryId)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
	}

	/**
	 * English raw markdown at {@code /entries/{id}/en.md}. Pulls from the {@code en}
	 * upstream tenant.
	 */
	@GetMapping(path = "/entries/{entryId:\\d+}/en.md", produces = "text/markdown;charset=UTF-8")
	@ResponseBody
	public ResponseEntity<String> markdownEn(@PathVariable Long entryId) {
		return this.entryClient.findMarkdownById(entryId, EN_TENANT_ID)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
	}

	/**
	 * Pre-builds social share intent URLs so the template only interpolates ready-to-use
	 * href values. {@link UriComponentsBuilder#encode} percent-encodes per RFC 3986
	 * (spaces become {@code %20}, reserved sub-delims like {@code &} are escaped in query
	 * values), which prevents titles containing {@code &}, {@code ?} or {@code
	 * #} from breaking the query string on X and Bluesky. The URL templates match the
	 * React reference 1:1 so post previews behave identically after migration.
	 */
	private static void addShareUrls(Model model, String canonicalUrl, String title) {
		String shareUrlX = UriComponentsBuilder.fromUriString("https://x.com/intent/post")
			.queryParam("url", canonicalUrl)
			.queryParam("text", title)
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();
		// Bluesky's compose intent takes a single `text` value; title + space + URL is
		// encoded as the composed post body.
		String shareUrlBluesky = UriComponentsBuilder.fromUriString("https://bsky.app/intent/compose")
			.queryParam("text", title + " " + canonicalUrl)
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();
		// Hatena Bookmark routes by path, not query string: the URL is appended to
		// `/entry/s/` with the scheme stripped (e.g. `ik.am/entries/42`).
		String hatenaTarget = canonicalUrl.replaceFirst("^https?://", "");
		model.addAttribute("shareUrlX", shareUrlX);
		model.addAttribute("shareUrlBluesky", shareUrlBluesky);
		model.addAttribute("shareUrlHatena", "https://b.hatena.ne.jp/entry/s/" + hatenaTarget);
	}

	/**
	 * Renders the branded 404 page without raising an exception. Sets the response status
	 * directly and returns the {@code error} template so tracing instrumentation does not
	 * mark the server span as error — a missing entry or invalid tag/category URL is a
	 * normal outcome, not a system fault.
	 */
	private static String renderNotFound(HttpServletRequest request, HttpServletResponse response, Model model) {
		response.setStatus(HttpStatus.NOT_FOUND.value());
		model.addAttribute("status", HttpStatus.NOT_FOUND.value());
		model.addAttribute("error", HttpStatus.NOT_FOUND.getReasonPhrase());
		model.addAttribute("path", request.getRequestURI());
		model.addAttribute("pageTitle", "Not Found");
		return "error";
	}

	/**
	 * Runs the conditional-request protocol for entry detail pages. Returns a 304
	 * response when the client's cached copy is still valid; returns {@code null} when
	 * the caller must render a fresh page.
	 *
	 * <p>
	 * {@code If-None-Match} wins over {@code If-Modified-Since} per RFC 7232. A parseable
	 * ETag whose {@code assetDigest} part differs from the server's current digest
	 * short-circuits to a full render without touching upstream — the client must
	 * re-download the HTML to pick up fresh fingerprinted JS/CSS URLs. When the digest
	 * matches, the entry-side epoch is forwarded to upstream as an
	 * {@code If-Modified-Since} to decide whether the entry itself changed.
	 */
	@Nullable private ResponseEntity<Void> evaluateConditional(Long entryId, @Nullable String tenantId,
			@Nullable String ifNoneMatchHeader, @Nullable String ifModifiedSinceHeader) {
		AssetsVersion.ParsedEtag parsed = this.assetsVersion.parseIfNoneMatch(ifNoneMatchHeader);
		if (parsed != null) {
			if (!parsed.assetDigest().equals(this.assetsVersion.digest())) {
				return null;
			}
			Instant clientUpdated = Instant.ofEpochSecond(parsed.entryEpochSec());
			ResponseEntity<Void> head = this.entryClient.headEntry(entryId, clientUpdated, tenantId);
			if (head.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
				return notModifiedResponse(clientUpdated);
			}
			return null;
		}
		Instant ifModifiedSince = parseHttpDate(ifModifiedSinceHeader);
		if (ifModifiedSince == null) {
			return null;
		}
		ResponseEntity<Void> head = this.entryClient.headEntry(entryId, ifModifiedSince, tenantId);
		if (head.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
			return notModifiedResponse(ifModifiedSince);
		}
		return null;
	}

	/**
	 * Builds the 304 response returned when upstream confirmed the entry is unchanged.
	 * Keeps {@code Cache-Control}, {@code Vary}, {@code Last-Modified}, and {@code ETag}
	 * on 304 so the browser refreshes its cache metadata without re-downloading the body.
	 */
	private ResponseEntity<Void> notModifiedResponse(Instant entryUpdated) {
		return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
			.cacheControl(ENTRY_CACHE_CONTROL)
			.header(HttpHeaders.VARY, CACHE_VARY)
			.lastModified(entryUpdated)
			.eTag(this.assetsVersion.formatEtag(entryUpdated))
			.build();
	}

	/**
	 * Writes {@code Last-Modified}, {@code ETag}, {@code Cache-Control}, and {@code Vary}
	 * on 200 responses for entry detail pages. The epoch sentinel (upstream emits it for
	 * entries never edited after import) skips {@code Last-Modified} so the browser keeps
	 * sending conditional requests as entries actually get updated — the {@code ETag}
	 * still carries the asset digest so redeploys with new JS/CSS bust the cache.
	 */
	private void applyEntryCacheHeaders(HttpServletResponse response, Entry entry) {
		Instant lastModified = entry.updated().date();
		if (lastModified != null && !Instant.EPOCH.equals(lastModified)) {
			response.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModified.toEpochMilli());
		}
		response.setHeader(HttpHeaders.ETAG, this.assetsVersion.formatEtag(lastModified));
		response.setHeader(HttpHeaders.CACHE_CONTROL, ENTRY_CACHE_CONTROL.getHeaderValue());
		response.setHeader(HttpHeaders.VARY, CACHE_VARY);
	}

	/**
	 * Parses an RFC 1123 {@code If-Modified-Since} header into an {@link Instant}.
	 * Returns {@code null} for missing or malformed values so the caller simply falls
	 * through to a full GET rather than 400-erroring on a bad client header.
	 */
	@Nullable private static Instant parseHttpDate(@Nullable String header) {
		if (!StringUtils.hasText(header)) {
			return null;
		}
		try {
			return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	/**
	 * Resolves the timestamp used for staleness comparison. When {@code updated.date} is
	 * missing or is the epoch sentinel ({@code 1970-01-01T00:00:00Z}) — which the
	 * upstream API emits for entries that have never been edited after import — fall back
	 * to {@code created.date} so the banner reflects the original publication.
	 */
	@Nullable private static Instant effectiveLastTouched(Entry entry) {
		Instant updated = entry.updated().date();
		if (updated == null || Instant.EPOCH.equals(updated)) {
			return entry.created().date();
		}
		return updated;
	}

	/**
	 * Calendar-year distance between {@code updatedAt} and {@code now} (both treated as
	 * UTC). Returns 0 for null input, matching the React reference which treats a missing
	 * {@code updated.date} as "not stale".
	 */
	private static long yearsSinceUpdate(@Nullable Instant updatedAt, Instant now) {
		if (updatedAt == null) {
			return 0L;
		}
		LocalDate updated = updatedAt.atZone(ZoneOffset.UTC).toLocalDate();
		LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
		long years = ChronoUnit.YEARS.between(updated, today);
		return Math.max(years, 0L);
	}

	/**
	 * The upstream API sets {@code hasNext} but does not always emit the
	 * {@code nextCursor} field. Fall back to the oldest entry's update timestamp in the
	 * current batch so {@code direction=NEXT} can still page forward.
	 */
	@Nullable private static String effectiveNextCursor(CursorPage<Entry> page) {
		if (page.nextCursor() != null) {
			return page.nextCursor();
		}
		if (!page.hasNext() || page.content().isEmpty()) {
			return null;
		}
		Entry last = page.content().get(page.content().size() - 1);
		Instant date = last.updated().date();
		return date == null ? null : date.toString();
	}

	private static PageDirection parseDirection(@Nullable String direction) {
		if (!StringUtils.hasText(direction)) {
			return PageDirection.NEXT;
		}
		try {
			return PageDirection.valueOf(direction.toUpperCase());
		}
		catch (IllegalArgumentException ignored) {
			return PageDirection.NEXT;
		}
	}

	private static String basePath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri == null || uri.isBlank() ? "/entries" : uri;
	}

	private static String buildPageTitle(@Nullable String query, @Nullable String tag, @Nullable List<String> cats) {
		if (StringUtils.hasText(query)) {
			return "Search: " + query;
		}
		if (StringUtils.hasText(tag)) {
			return "Tag: " + tag;
		}
		if (cats != null && !cats.isEmpty()) {
			return "Category: " + String.join(" / ", cats);
		}
		return "Entries";
	}

	@Nullable private static String buildPageUrl(String basePath, @Nullable String cursor, PageDirection direction,
			@Nullable String query) {
		if (!StringUtils.hasText(cursor)) {
			return null;
		}
		Map<String, String> params = new LinkedHashMap<>();
		params.put("cursor", cursor);
		params.put("direction", direction.name());
		if (StringUtils.hasText(query)) {
			params.put("query", query);
		}
		StringBuilder sb = new StringBuilder(basePath).append('?');
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) {
				sb.append('&');
			}
			first = false;
			sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

}
