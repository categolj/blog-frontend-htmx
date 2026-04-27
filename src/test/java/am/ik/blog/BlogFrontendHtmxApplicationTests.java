package am.ik.blog;

import am.ik.blog.asset.AssetsVersion;
import am.ik.blog.testsupport.MockServer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(BlogFrontendHtmxApplicationTests.FixedTimeConfig.class)
class BlogFrontendHtmxApplicationTests {

	// Pin the InstantSource so staleness maths is deterministic across runs.
	private static final Instant FIXED_NOW = Instant.parse("2026-04-22T00:00:00Z");

	// Pin the asset digest so ETag values are deterministic across runs.
	private static final String TEST_ASSET_DIGEST = "test0digest";

	// Matches the sample entry's updated.date used across these tests.
	private static final Instant SAMPLE_ENTRY_UPDATED = Instant.parse("2026-04-10T09:30:00Z");

	private static final String CURRENT_ETAG = "W/\"" + SAMPLE_ENTRY_UPDATED.getEpochSecond() + "-" + TEST_ASSET_DIGEST
			+ "\"";

	@TestConfiguration
	static class FixedTimeConfig {

		@Bean
		InstantSource testInstantSource() {
			return () -> FIXED_NOW;
		}

		@Bean
		@Primary
		AssetsVersion testAssetsVersion() {
			return AssetsVersion.withFixedDigest(TEST_ASSET_DIGEST);
		}

	}

	// Started eagerly so its baseUrl() is available when @DynamicPropertySource fires.
	private static final MockServer mockApi = MockServer.start();

	@Autowired
	RestTestClient client;

	@AfterAll
	static void stopMockApi() {
		mockApi.close();
	}

	@DynamicPropertySource
	static void registerProps(DynamicPropertyRegistry registry) {
		registry.add("blog.api.base-url", mockApi::baseUrl);
	}

	@BeforeEach
	void resetMockApi() {
		mockApi.reset();
	}

	@Test
	void homePageRenders() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/", false);

		assertThat(doc.title()).isEqualTo("Entries · IK.AM");
		assertSingleSampleEntryCard(doc);
		assertThat(doc.selectFirst("a.read-more-btn")).as("read-more button").isNull();
	}

	@Test
	void entriesPathRendersSameAsHome() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/entries", false);

		assertThat(doc.title()).isEqualTo("Entries · IK.AM");
		assertSingleSampleEntryCard(doc);
		assertThat(doc.selectFirst("a.read-more-btn")).as("read-more button").isNull();
	}

	@Test
	void readMoreButtonFallsBackToLastEntryDateWhenApiOmitsNextCursor() {
		mockApi.stubGetJson("/entries", entriesJson(true, null));

		Document doc = parsePage("/entries", false);

		// Falls back to the last entry's `updated.date` when the API omits nextCursor.
		assertReadMoreButtonHref(doc, "/entries?cursor=2026-04-10T09%3A30%3A00Z&direction=NEXT");
	}

	@Test
	void readMoreButtonIsRenderedWhenNextCursorExists() {
		mockApi.stubGetJson("/entries", entriesJson(true, "2026-04-05T00:00:00Z"));

		Document doc = parsePage("/entries", false);

		assertReadMoreButtonHref(doc, "/entries?cursor=2026-04-05T00%3A00%3A00Z&direction=NEXT");
	}

	@Test
	void readMoreFragmentAppendsWithoutSectionWrapper() {
		mockApi.stubGetJson("/entries",
				cursorPage(true, false, null, "2026-03-20T00:00:00Z", List.of(SAMPLE_ENTRY_NO_CONTENT_JSON)));

		Document doc = parsePage("/entries?cursor=2026-04-01T00:00:00Z&direction=NEXT", true);

		// HTMX append fragment: no layout chrome, no #entries wrapper, just the next
		// batch.
		assertIsFragment(doc);
		assertThat(doc.selectFirst("section#entries")).as("entries section wrapper").isNull();
		assertThat(doc.select("article.entry-card")).hasSize(1);
		assertThat(doc.selectFirst("div.more-wrap")).as("more-wrap container").isNotNull();
	}

	@Test
	void homeFragmentForHtmxRequestHasNoLayout() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/", true);

		// Initial HTMX swap returns the wrapped #entries section without layout chrome.
		assertIsFragment(doc);
		Element entries = requireSelected(doc, "section#entries");
		assertThat(entries.select("article.entry-card")).hasSize(1);
		assertThat(requireSelected(entries, "article.entry-card a[href=/entries/42]").text()).isEqualTo("Sample Post");
	}

	@Test
	void entryDetailPageRenders() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42", false);

		assertThat(doc.title()).isEqualTo("Sample Post · IK.AM");
		assertThat(requireSelected(doc, "meta[name=description]").attr("content")).isEqualTo("A sample summary.");

		Element article = requireSelected(doc, "article.entry");
		assertThat(article.id()).isEqualTo("entry-42");
		assertThat(requireSelected(article, "h1.entry-title").text()).isEqualTo("Sample Post");
		assertThat(requireSelected(article, "a.md-badge").attr("href")).isEqualTo("/entries/42.md");
		Element views = requireSelected(article, "span.views-counter");
		assertThat(views.attr("hx-post")).isEqualTo("/counter/42");
		assertThat(views.attr("hx-trigger")).isEqualTo("load");
		assertThat(views.attr("hx-swap")).isEqualTo("outerHTML");
		assertThat(article.select("nav.entry-breadcrumb")).hasSize(1);
		assertThat(article.select("nav.entry-breadcrumb a")).extracting(Element::text, e -> e.attr("href"))
			.containsExactly(tuple("Programming", "/categories/Programming/entries"));
		assertThat(article.select("a.entry-tag")).extracting(Element::text, e -> e.attr("href"))
			.containsExactly(tuple("Spring Boot", "/tags/Spring%20Boot/entries"));

		// Markdown content rendered (heading anchored, inline emphasis preserved).
		Element body = requireSelected(article, "div.entry-body");
		Element h2 = requireSelected(body, "h2");
		assertThat(h2.id()).isEqualTo("hello");
		assertThat(h2.text()).isEqualTo("Hello");
		assertThat(requireSelected(body, "p strong").text()).isEqualTo("bold");
	}

	@Test
	void entryDetailOmitsStaleWarningForRecentEntries() {
		// Sample entry was updated 2026-04-10, 12 days before the pinned FIXED_NOW — well
		// under the 2-year threshold.
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42", false);

		assertThat(doc.selectFirst("article.entry aside.entry-stale")).as("stale banner should be absent").isNull();
	}

	@Test
	void entryDetailShowsStaleWarningWithActualYearCountForOldEntries() {
		// 2023-01-15 is 3 calendar years before FIXED_NOW (2026-04-22). The banner text
		// must reflect the actual elapsed years — not the hard-coded "2 years" threshold.
		mockApi.stubGetJson("/entries/42", staleEntryJson("2023-01-15T09:30:00Z"));

		Document doc = parsePage("/entries/42", false);

		Element banner = requireSelected(doc, "article.entry aside.entry-stale");
		assertThat(banner.attr("role")).isEqualTo("note");
		assertThat(banner.text()).contains("3 years ago");
	}

	@Test
	void entryDetailFallsBackToCreatedDateWhenUpdatedIsEpoch() {
		// The upstream API emits `1970-01-01T00:00:00Z` for entries that have never been
		// edited after import. Treat that as "no update" and compare against created
		// instead, so the banner still fires for old posts.
		mockApi.stubGetJson("/entries/42", entryJson("2020-06-15T09:30:00Z" /* created */,
				"1970-01-01T00:00:00Z" /* updated = epoch */));

		Document doc = parsePage("/entries/42", false);

		Element banner = requireSelected(doc, "article.entry aside.entry-stale");
		// 2020-06-15 → 2026-04-22 = 5 calendar years.
		assertThat(banner.text()).contains("5 years ago");
	}

	@Test
	void entryDetailShowsStaleWarningExactlyAtTwoYearThreshold() {
		// 2024-01-10 → 2026-04-22 is 2 calendar years — the banner must render at the
		// threshold itself, not only strictly beyond it.
		mockApi.stubGetJson("/entries/42", staleEntryJson("2024-01-10T09:30:00Z"));

		Document doc = parsePage("/entries/42", false);

		Element banner = requireSelected(doc, "article.entry aside.entry-stale");
		assertThat(banner.text()).contains("2 years ago");
	}

	@Test
	void entryDetailEmitsFullSocialMetaTags() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42", false);

		assertThat(requireSelected(doc, "meta[property=og:type]").attr("content")).isEqualTo("article");
		assertThat(requireSelected(doc, "meta[property=og:title]").attr("content")).isEqualTo("Sample Post - IK.AM");
		assertThat(requireSelected(doc, "meta[property=og:description]").attr("content"))
			.isEqualTo("A sample summary.");
		assertThat(requireSelected(doc, "meta[property=og:url]").attr("content")).isEqualTo("https://ik.am/entries/42");
		assertThat(requireSelected(doc, "meta[property=og:image]").attr("content")).isNotEmpty();
		assertThat(requireSelected(doc, "meta[property=og:site_name]").attr("content")).isEqualTo("IK.AM");
		assertThat(requireSelected(doc, "meta[name=twitter:card]").attr("content")).isEqualTo("summary_large_image");
		assertThat(requireSelected(doc, "meta[name=twitter:site]").attr("content")).isEqualTo("@making");
		assertThat(requireSelected(doc, "meta[name=twitter:creator]").attr("content")).isEqualTo("@making");
		assertThat(requireSelected(doc, "link[rel=canonical]").attr("href")).isEqualTo("https://ik.am/entries/42");
	}

	@Test
	void entryDetailRendersUrlEncodedShareLinks() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42", false);

		Element share = requireSelected(doc, "article.entry nav.share");
		assertThat(share.attr("aria-label")).isEqualTo("Share");

		// UriComponentsBuilder encodes query values per RFC 3986: spaces become `%20`
		// and sub-delims like `&` become `%26`, while `:` / `/` / `?` stay literal in
		// query values.
		String canonical = "https://ik.am/entries/42";
		String encodedText = "Sample%20Post";

		assertThat(share.select("a[href]")).extracting(e -> e.attr("href"))
			.containsExactly("https://x.com/intent/post?url=" + canonical + "&text=" + encodedText,
					"https://bsky.app/intent/compose?text=" + encodedText + "%20" + canonical,
					// Hatena Bookmark routes by path: canonical URL with `https://`
					// stripped.
					"https://b.hatena.ne.jp/entry/s/ik.am/entries/42");
	}

	@Test
	void entryDetailEmbedsGiscusPlaceholderBelowArticleBodyAndAboveShareButtons() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42", false);

		Element article = requireSelected(doc, "article.entry");
		Element comments = requireSelected(article, "section.comments");

		// The placeholder carries Giscus config on data-* attributes; giscus.js reads
		// them
		// client-side, resolves the site's current theme (light/dark/system), and injects
		// the actual <script> element. Rendering the script server-side would lock the
		// theme to the OS preference and flash on first paint for users who manually
		// selected dark.
		assertThat(comments.attr("data-giscus-repo")).isEqualTo("making/blog.ik.am");
		assertThat(comments.attr("data-giscus-repo-id")).isEqualTo("MDEwOlJlcG9zaXRvcnk0ODMzMTM4Ng==");
		assertThat(comments.attr("data-giscus-category")).isEqualTo("General");
		assertThat(comments.attr("data-giscus-category-id")).isEqualTo("DIC_kwDOAuF6es4C1OVk");
		assertThat(comments.attr("data-giscus-mapping")).isEqualTo("pathname");
		// No server-rendered <script> — giscus.js appends it after reading the theme.
		assertThat(comments.select("script")).as("Giscus script should be client-injected, not SSR'd").isEmpty();

		// The Giscus embed must sit between the entry body and the share nav so comments
		// render right after the article but above the share buttons.
		List<Element> children = article.children();
		int bodyIndex = children.indexOf(requireSelected(article, "div.entry-body"));
		int commentsIndex = children.indexOf(comments);
		int shareIndex = children.indexOf(requireSelected(article, "nav.share"));
		assertThat(bodyIndex).isLessThan(commentsIndex);
		assertThat(commentsIndex).isLessThan(shareIndex);
	}

	@Test
	void entryDetailShareLinksEncodeTitleSpecialCharacters() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_AMPERSAND_TITLE_JSON);

		Document doc = parsePage("/entries/42", false);

		Element share = requireSelected(doc, "article.entry nav.share");
		// Title "A & B?" — the ambiguous `&` must be percent-encoded so it is not
		// interpreted as a query-param separator. `?` is allowed unencoded in a query
		// value per RFC 3986, so UriComponentsBuilder leaves it literal.
		String encodedText = "A%20%26%20B?";
		String canonical = "https://ik.am/entries/42";
		String xHref = Objects.requireNonNull(share.selectFirst("a.share-x")).attr("href");
		String blueskyHref = Objects.requireNonNull(share.selectFirst("a.share-bluesky")).attr("href");
		assertThat(xHref).isEqualTo("https://x.com/intent/post?url=" + canonical + "&text=" + encodedText);
		assertThat(blueskyHref).isEqualTo("https://bsky.app/intent/compose?text=" + encodedText + "%20" + canonical);
	}

	@Test
	void entryDetailEmitsLastModifiedEtagAndCacheControlOnInitialVisit() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		this.client.get()
			.uri("/entries/42")
			.exchange()
			.expectStatus()
			.isOk()
			// entry.updated.date = 2026-04-10T09:30:00Z → RFC 1123 form.
			.expectHeader()
			.valueEquals(HttpHeaders.LAST_MODIFIED, "Fri, 10 Apr 2026 09:30:00 GMT")
			// ETag combines entry epoch seconds and the pinned asset digest.
			.expectHeader()
			.valueEquals(HttpHeaders.ETAG, CURRENT_ETAG)
			.expectHeader()
			.valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=3600, stale-while-revalidate=600")
			.expectHeader()
			.valueEquals(HttpHeaders.VARY, "hx-request,hx-boosted,accept-encoding");
	}

	@Test
	void entryDetailReturns304WhenIfNoneMatchMatchesCurrentAsset() {
		// The stubbed GET would carry a body; its presence proves it is never called.
		mockApi.stubHead("/entries/42", 304);
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		this.client.get()
			.uri("/entries/42")
			.header(HttpHeaders.IF_NONE_MATCH, CURRENT_ETAG)
			.exchange()
			.expectStatus()
			.isNotModified()
			.expectHeader()
			.valueEquals(HttpHeaders.ETAG, CURRENT_ETAG)
			.expectHeader()
			.valueEquals(HttpHeaders.LAST_MODIFIED, "Fri, 10 Apr 2026 09:30:00 GMT")
			.expectHeader()
			.valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=3600, stale-while-revalidate=600")
			.expectHeader()
			.valueEquals(HttpHeaders.VARY, "HX-Request, HX-Boosted");

		assertThat(mockApi.requestCount("HEAD", "/entries/42")).as("HEAD called to short-circuit").isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42")).as("full body fetch is skipped on 304").isZero();
	}

	@Test
	void entryDetailSkipsUpstreamHeadWhenAssetDigestChanged() {
		mockApi.stubHead("/entries/42", 304);
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		String staleEtag = "W/\"" + SAMPLE_ENTRY_UPDATED.getEpochSecond() + "-deadbeefcafef00d\"";
		this.client.get()
			.uri("/entries/42")
			.header(HttpHeaders.IF_NONE_MATCH, staleEtag)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ETAG, CURRENT_ETAG);

		assertThat(mockApi.requestCount("HEAD", "/entries/42"))
			.as("asset digest mismatch short-circuits locally, no upstream HEAD")
			.isZero();
		assertThat(mockApi.requestCount("GET", "/entries/42")).as("fresh HTML fetched").isEqualTo(1);
	}

	@Test
	void entryDetailFallsThroughToFullGetWhenEntryPartOfEtagIsOlder() {
		mockApi.stubHead("/entries/42", 200);
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		// Same digest so upstream is consulted, but older epoch → upstream says 200.
		String olderEtag = "W/\"1704067200-" + TEST_ASSET_DIGEST + "\"";
		this.client.get()
			.uri("/entries/42")
			.header(HttpHeaders.IF_NONE_MATCH, olderEtag)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.ETAG, CURRENT_ETAG);

		assertThat(mockApi.requestCount("HEAD", "/entries/42")).isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42")).isEqualTo(1);
	}

	@Test
	void entryDetailReturns304WhenIfModifiedSinceFallbackMatches() {
		// Clients without If-None-Match keep the RFC 7232 fallback path working.
		mockApi.stubHead("/entries/42", 304);
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		this.client.get()
			.uri("/entries/42")
			.header(HttpHeaders.IF_MODIFIED_SINCE, "Fri, 10 Apr 2026 09:30:00 GMT")
			.exchange()
			.expectStatus()
			.isNotModified()
			.expectHeader()
			.valueEquals(HttpHeaders.ETAG, CURRENT_ETAG)
			.expectHeader()
			.valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=3600, stale-while-revalidate=600")
			.expectHeader()
			.valueEquals(HttpHeaders.VARY, "HX-Request, HX-Boosted");

		assertThat(mockApi.requestCount("HEAD", "/entries/42")).as("HEAD called to short-circuit").isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42")).as("full body fetch is skipped on 304").isZero();
	}

	@Test
	void entryDetailFallsThroughToFullGetWhenUpstreamSaysModified() {
		mockApi.stubHead("/entries/42", 200);
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		this.client.get()
			.uri("/entries/42")
			// Older than entry.updated.date so upstream says the resource has changed.
			.header(HttpHeaders.IF_MODIFIED_SINCE, "Thu, 01 Jan 2026 00:00:00 GMT")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.LAST_MODIFIED, "Fri, 10 Apr 2026 09:30:00 GMT")
			.expectHeader()
			.valueEquals(HttpHeaders.ETAG, CURRENT_ETAG);

		assertThat(mockApi.requestCount("HEAD", "/entries/42")).isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42")).isEqualTo(1);
	}

	@Test
	void entryDetailPartialSwapSkipsConditionalHeaders() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		this.client.get()
			.uri("/entries/42")
			.header("HX-Request", "true")
			.header(HttpHeaders.IF_NONE_MATCH, CURRENT_ETAG)
			.header(HttpHeaders.IF_MODIFIED_SINCE, "Thu, 01 Jan 2026 00:00:00 GMT")
			.exchange()
			.expectStatus()
			.isOk()
			// Partial swaps return fragment HTML that must not share cache keys with the
			// full page, so we omit cache/last-modified/etag entirely here.
			.expectHeader()
			.doesNotExist(HttpHeaders.LAST_MODIFIED)
			.expectHeader()
			.doesNotExist(HttpHeaders.ETAG)
			.expectHeader()
			.valueEquals(HttpHeaders.VARY, "accept-encoding");

		assertThat(mockApi.requestCount("HEAD", "/entries/42")).as("HEAD is skipped for partial swaps").isZero();
		assertThat(mockApi.requestCount("GET", "/entries/42")).isEqualTo(1);
	}

	@Test
	void listPageEmitsCacheControlOnFullPageResponse() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		this.client.get()
			.uri("/entries")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=60, stale-while-revalidate=300")
			.expectHeader()
			.valueEquals(HttpHeaders.VARY, "hx-request,hx-boosted,accept-encoding");
	}

	@Test
	void listPageEmitsSiteWideSocialMetaDefaults() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/entries", false);

		assertThat(requireSelected(doc, "meta[property=og:type]").attr("content")).isEqualTo("website");
		assertThat(requireSelected(doc, "meta[property=og:title]").attr("content")).isEqualTo("Entries · IK.AM");
		assertThat(requireSelected(doc, "meta[property=og:description]").attr("content"))
			.isEqualTo("@making's tech note");
		assertThat(requireSelected(doc, "meta[property=og:url]").attr("content")).isEqualTo("https://ik.am/entries");
		assertThat(requireSelected(doc, "meta[property=og:image]").attr("content")).isNotEmpty();
		assertThat(requireSelected(doc, "meta[property=og:site_name]").attr("content")).isEqualTo("IK.AM");
		assertThat(requireSelected(doc, "meta[name=twitter:card]").attr("content")).isEqualTo("summary");
		assertThat(requireSelected(doc, "meta[name=twitter:site]").attr("content")).isEqualTo("@making");
		assertThat(requireSelected(doc, "link[rel=canonical]").attr("href")).isEqualTo("https://ik.am/entries");
	}

	@Test
	void entryDetailReturns404WhenMissing() {
		mockApi.stubGetNotFound("/entries/99");

		this.client.get().uri("/entries/99").exchange().expectStatus().isNotFound();
	}

	@Test
	void entryDetailRendersBrandedNotFoundTemplateWhenMissing() {
		mockApi.stubGetNotFound("/entries/99999999");

		Document doc = parseErrorPage("/entries/99999999", 404);

		assertBrandedErrorPage(doc, "404", "Not Found");
	}

	@Test
	void forbiddenUrlReturns403AndBrandedTemplate() {
		Document doc = parseErrorPage("/forbidden", 403);

		assertBrandedErrorPage(doc, "403", "Forbidden");
	}

	@Test
	void forbiddenUrlReturns403JsonWhenJsonRequested() {
		this.client.get()
			.uri("/forbidden")
			.accept(MediaType.APPLICATION_JSON)
			.exchange()
			.expectStatus()
			.isForbidden()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo(403)
			.jsonPath("$.error")
			.isEqualTo("Forbidden")
			.jsonPath("$.path")
			.isEqualTo("/forbidden")
			.jsonPath("$.timestamp")
			.exists();
	}

	@Test
	void unknownPathReturns404AndBrandedTemplate() {
		Document doc = parseErrorPage("/nonexistent/path", 404);

		assertBrandedErrorPage(doc, "404", "Not Found");
	}

	@Test
	void markdownEndpointReturnsRawMarkdown() {
		String markdown = "---\ntitle: Sample Post\n---\n## Hello\n\nSome **bold** body.\n";
		mockApi.stubGet("/entries/42.md", 200, markdown, "text/markdown;charset=UTF-8");

		String body = this.client.get()
			.uri("/entries/42.md")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("text/markdown")
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isEqualTo(markdown);
	}

	@Test
	void markdownEndpointReturns404WhenMissing() {
		mockApi.stubGetNotFound("/entries/99.md");

		this.client.get().uri("/entries/99.md").exchange().expectStatus().isNotFound();
	}

	@Test
	void tagsPageRenders() {
		mockApi.stubGetJson("/tags", """
				[
				  {"name": "Spring Boot", "count": 3},
				  {"name": "Java", "count": 2}
				]
				""");

		Document doc = parsePage("/tags", false);

		assertThat(doc.title()).isEqualTo("Tags · IK.AM");
		// Server renders tags in case-insensitive name order so no-JS users get a
		// predictable default; the sort toggle is wired client-side.
		assertThat(doc.select("ul#tag-list li.tag-list-item"))
			.extracting(e -> Objects.requireNonNull(e.selectFirst("a.tag-list-link")).attr("href"),
					e -> Objects.requireNonNull(e.selectFirst(".tag-list-name")).text(),
					e -> Objects.requireNonNull(e.selectFirst(".tag-list-count")).text(), e -> e.attr("data-name"),
					e -> e.attr("data-count"))
			.containsExactly(tuple("/tags/Java/entries", "Java", "2", "Java", "2"),
					tuple("/tags/Spring%20Boot/entries", "Spring Boot", "3", "Spring Boot", "3"));

		// Toolbar: filter input + Name/Count sort buttons with Name active by default.
		assertThat(doc.selectFirst("input#tag-filter-input")).as("filter input").isNotNull();
		assertThat(doc.select(".tag-sort-btn"))
			.extracting(Element::text, e -> e.attr("data-sort"), e -> e.classNames().contains("is-active"),
					e -> e.attr("aria-pressed"))
			.containsExactly(tuple("Name", "name", true, "true"), tuple("Count", "count", false, "false"));
	}

	@Test
	void tagsPageEmptyState() {
		mockApi.stubGetJson("/tags", "[]");

		Document doc = parsePage("/tags", false);

		// With no tags, suppress the toolbar and show the empty message instead.
		assertThat(doc.selectFirst("ul#tag-list")).as("tag list").isNull();
		assertThat(doc.selectFirst(".tag-toolbar")).as("toolbar").isNull();
		assertThat(requireSelected(doc, "p.empty").text()).isEqualTo("No tags yet.");
	}

	@Test
	void categoriesPageRendersFlatListOfBreadcrumbChains() {
		mockApi.stubGetJson("/categories", """
				[
				  [{"name": "Programming"}, {"name": "Language"}, {"name": "Java"}],
				  [{"name": "Programming"}, {"name": "Framework"}, {"name": "Spring Boot"}],
				  [{"name": "Operations"}, {"name": "Kubernetes"}]
				]
				""");

		Document doc = parsePage("/categories", false);

		assertThat(doc.title()).isEqualTo("Categories · IK.AM");

		// Flat list: one <li> per API chain — no nesting, no <details>.
		List<Element> rows = doc.select("section#category-list ul.category-list-items > li");
		assertThat(rows).hasSize(3);

		// Each row is a breadcrumb of cumulative links, one per segment in the chain —
		// prefixes use comma-joined names in a single path segment.
		assertThat(rows.get(0).select("nav.category-crumbs a")).extracting(Element::text, e -> e.attr("href"))
			.containsExactly(tuple("Programming", "/categories/Programming/entries"),
					tuple("Language", "/categories/Programming,Language/entries"),
					tuple("Java", "/categories/Programming,Language,Java/entries"));
		// N-1 separators for N crumbs (no trailing separator).
		assertThat(rows.get(0).select("nav.category-crumbs > span.crumb-sep")).hasSize(2);

		// Spaces in segments render as %20 (RFC 3986 path-segment encoding).
		assertThat(rows.get(1).select("nav.category-crumbs a")).extracting(e -> e.attr("href"))
			.contains("/categories/Programming,Framework,Spring%20Boot/entries");

		// 2-level chains work too.
		assertThat(rows.get(2).select("nav.category-crumbs a")).extracting(Element::text)
			.containsExactly("Operations", "Kubernetes");
	}

	@Test
	void categoriesPageShowsEmptyStateWhenNoCategories() {
		mockApi.stubGetJson("/categories", "[]");

		Document doc = parsePage("/categories", false);

		assertThat(doc.selectFirst("ul.category-list-items")).as("category list").isNull();
		assertThat(requireSelected(doc, "section#category-list p.empty").text()).isEqualTo("No categories yet.");
	}

	@Test
	void tagEntriesPathFiltersByTag() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/tags/Java/entries", false);

		// <title> still reflects the filter so browser tabs/search stay descriptive, but
		// the hero chip carries the visible label now.
		assertThat(doc.title()).isEqualTo("Tag: Java · IK.AM");
		// Hero replaces the redundant "Tag: X" heading with a minimal chip — mirrors the
		// category treatment where the h1 is suppressed when a filter is active.
		Element hero = requireSelected(doc, "section.hero");
		assertThat(hero.selectFirst("h1.hero-title")).as("hero title suppressed when tag active").isNull();
		assertThat(hero.selectFirst("p.hero-sub")).as("hero sub suppressed when tag active").isNull();
		Element tagNav = requireSelected(hero, "nav.entry-breadcrumb[aria-label=Tag]");
		assertThat(tagNav.text()).isEqualTo("#Java");
		// Entry list renders against the mocked upstream response.
		assertSingleSampleEntryCard(doc);
	}

	@Test
	void tagEntriesPathDecodesPercentEncodedTagName() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		// Crawlers and the sitemap emit percent-encoded segments (spaces → %20). The tag
		// chip in the hero must show the original tag name.
		Document doc = parsePage("/tags/Spring%20Boot/entries", false);

		assertThat(doc.title()).isEqualTo("Tag: Spring Boot · IK.AM");
		Element tagNav = requireSelected(doc, "section.hero nav.entry-breadcrumb[aria-label=Tag]");
		assertThat(tagNav.text()).isEqualTo("#Spring Boot");
	}

	@Test
	void tagEntriesPathDecodesPercentEncodedSolidusInTagName() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		// Tag names may contain a literal '/' (e.g. "HTTP/3"). The href is emitted as
		// /tags/HTTP%2F3/entries; Tomcat's encodedSolidusHandling=PASS_THROUGH keeps the
		// %2F literal so the path variable captures a single segment, and URLDecoder
		// recovers the original tag value for upstream filtering and display.
		Document doc = parsePage("/tags/HTTP%2F3/entries", false);

		assertThat(doc.title()).isEqualTo("Tag: HTTP/3 · IK.AM");
		Element tagNav = requireSelected(doc, "section.hero nav.entry-breadcrumb[aria-label=Tag]");
		assertThat(tagNav.text()).isEqualTo("#HTTP/3");
	}

	@Test
	void searchQueryRendersSearchChipInsteadOfHeroTitle() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/entries?query=kubernetes", false);

		assertThat(doc.title()).isEqualTo("Search: kubernetes · IK.AM");
		Element hero = requireSelected(doc, "section.hero");
		assertThat(hero.selectFirst("h1.hero-title")).as("hero title suppressed when search active").isNull();
		Element searchNav = requireSelected(hero, "nav.entry-breadcrumb[aria-label=Search]");
		assertThat(searchNav.text()).isEqualTo("kubernetes");
	}

	@Test
	void tagEntriesPathReadMoreKeepsTagInPath() {
		mockApi.stubGetJson("/entries", entriesJson(true, "2026-04-05T00:00:00Z"));

		Document doc = parsePage("/tags/Java/entries", false);

		// Read-more keeps the tag in the path (not re-emitted as a `tag=` query param)
		// so the URL stays canonical across pages.
		assertReadMoreButtonHref(doc, "/tags/Java/entries?cursor=2026-04-05T00%3A00%3A00Z&direction=NEXT");
	}

	@Test
	void tagEntriesPathReturnsFragmentForHtmxRequest() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/tags/Java/entries", true);

		// HTMX partial: wrapped #entries section without layout chrome.
		assertIsFragment(doc);
		Element entries = requireSelected(doc, "section#entries");
		assertThat(entries.select("article.entry-card")).hasSize(1);
	}

	@Test
	void categoryEntriesPathRendersTwoLevelChain() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/categories/Programming,Java/entries", false);

		assertThat(doc.title()).isEqualTo("Category: Programming / Java · IK.AM");
		// Hero replaces the plain H1 with an entry-detail-style breadcrumb so each chain
		// prefix is clickable. Prefix URLs use comma-joined names in a single segment.
		Element crumbs = requireSelected(doc, "section.hero nav.entry-breadcrumb");
		assertThat(crumbs.select("a")).extracting(Element::text, e -> e.attr("href"))
			.containsExactly(tuple("Programming", "/categories/Programming/entries"),
					tuple("Java", "/categories/Programming,Java/entries"));
		assertThat(doc.selectFirst("section.hero h1.hero-title")).as("hero title suppressed when breadcrumb shown")
			.isNull();
		// Entry list renders against the mocked upstream response.
		assertSingleSampleEntryCard(doc);
	}

	@Test
	void categoryEntriesPathRendersThreeLevelChain() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/categories/Programming,Language,Java/entries", false);

		assertThat(doc.title()).isEqualTo("Category: Programming / Language / Java · IK.AM");
		assertSingleSampleEntryCard(doc);
	}

	@Test
	void categoryEntriesPathReadMorePreservesCategoryChainInUrl() {
		mockApi.stubGetJson("/entries", entriesJson(true, "2026-04-05T00:00:00Z"));

		Document doc = parsePage("/categories/Programming,Java/entries", false);

		// Read-more keeps the chain in the path (not re-emitted as a `categories=` query
		// param) so the URL stays canonical across pages.
		assertReadMoreButtonHref(doc,
				"/categories/Programming,Java/entries?cursor=2026-04-05T00%3A00%3A00Z&direction=NEXT");
	}

	@Test
	void categoryEntriesPathReturns404ForEmptyChainSegment() {
		// A `{categories}` value that's empty after comma-splitting (e.g. ",") has no
		// usable chain — treat as 404 rather than returning all entries.
		this.client.get().uri("/categories/,/entries").exchange().expectStatus().isNotFound();
	}

	@Test
	void aboutmePageRendersProfileHtml() {
		Document doc = parsePage("/aboutme", false);

		assertThat(doc.title()).isEqualTo("About Me · IK.AM");
		Element about = requireSelected(doc, "section.about");

		// Profile header: name, role, contact badges, avatar.
		assertThat(requireSelected(about, ".about-name").text()).isEqualTo("Toshiaki Maki / 槙 俊明");
		assertThat(requireSelected(about, ".about-role").text()).isEqualTo("Senior Principal Architect at Broadcom");
		assertThat(requireSelected(about, "img.about-avatar").attr("src"))
			.isEqualTo("https://avatars.githubusercontent.com/u/106908?s=200");
		assertThat(about.select(".about-contact")).extracting(Element::text)
			.containsExactly("@making", "makingx [at] gmail.com");

		// Every company appears as an org entry; UTokyo appears in Education.
		assertThat(about.select(".about-timeline-org")).extracting(Element::text)
			.containsExactly("Broadcom", "VMware", "Pivotal", "NTT DATA", "The University of Tokyo");

		// School links point at the U-Tokyo pages called out in the React reference.
		assertThat(about.select(".about-role-location a")).extracting(e -> e.attr("href"))
			.contains("https://www.i.u-tokyo.ac.jp/", "https://www.u-tokyo.ac.jp/");
	}

	@Test
	void aboutmePageInheritsDefaultLayout() {
		Document doc = parsePage("/aboutme", false);

		// Layout chrome (header, footer, theme toggle) must render so the About page
		// stays visually consistent with the rest of the site.
		assertThat(doc.selectFirst("header.site-header")).as("site header").isNotNull();
		assertThat(doc.selectFirst("footer.site-footer")).as("site footer").isNotNull();
		assertThat(doc.selectFirst("header.site-header button.theme-toggle")).as("theme toggle").isNotNull();
	}

	@Test
	void siteHeaderIncludesAboutNavLink() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		// Nav is part of the shared layout — verify on the home page so any route proves
		// the link is there.
		Document doc = parsePage("/", false);

		assertThat(doc.select("header.site-header nav.site-nav a")).extracting(Element::text, e -> e.attr("href"))
			.contains(tuple("About", "/aboutme"));
	}

	@Test
	void siteHeaderIncludesSearchForm() {
		// Header widgets live in the shared layout, so any route proves the form is
		// there.
		Document doc = parsePage("/aboutme", false);

		Element form = requireSelected(doc, ".site-header form[action='/entries']");
		assertThat(form.attr("method")).isEqualToIgnoringCase("get");
		assertThat(form.attr("role")).isEqualTo("search");
		Element input = requireSelected(form, "input[name=query][type=search]");
		assertThat(input.attr("autocomplete")).isEqualTo("off");
		// Spinner element styled via .htmx-request so hx-boost-powered submissions show a
		// loading indicator.
		assertThat(requireSelected(form, ".search-spinner").attr("aria-hidden")).isEqualTo("true");
	}

	@Test
	void rssFeedRendersAtomXmlForLatestEntries() {
		mockApi.stubGetJson("/entries", FEED_ENTRIES_JSON);

		byte[] body = rawBytes("/rss");
		Document feed = Jsoup.parse(new String(body, StandardCharsets.UTF_8), "", Parser.xmlParser());

		Element feedEl = requireSelected(feed, "feed");
		assertThat(feedEl.attr("xmlns")).isEqualTo("http://www.w3.org/2005/Atom");
		assertThat(requireSelected(feedEl, "> id").text()).isEqualTo("https://ik.am/");
		assertThat(requireSelected(feedEl, "> title").text()).isEqualTo("IK.AM");
		assertThat(requireSelected(feedEl, "> subtitle").text()).isEqualTo("@making's tech note");
		// Feed-level <updated> is the max of entry updated dates.
		assertThat(requireSelected(feedEl, "> updated").text()).isEqualTo("2026-04-20T10:00:00Z");
		assertThat(requireSelected(feedEl, "> link[rel=self]").attr("href")).isEqualTo("https://ik.am/rss");
		// The site-root link has no rel attribute.
		assertThat(feedEl.select("> link").stream().map(e -> e.attr("href")).toList()).contains("https://ik.am/rss",
				"https://ik.am/");

		assertThat(feedEl.select("> entry")).hasSize(3);
		assertThat(feedEl.select("> entry > title")).extracting(Element::text)
			.containsExactly("First Post", "Second Post", "Third Post");
		assertThat(feedEl.select("> entry > link")).extracting(e -> e.attr("href"))
			.containsExactly("https://ik.am/entries/1", "https://ik.am/entries/2", "https://ik.am/entries/3");
		assertThat(feedEl.select("> entry > id")).extracting(Element::text)
			.containsExactly("https://ik.am/entries/1", "https://ik.am/entries/2", "https://ik.am/entries/3");
		assertThat(feedEl.select("> entry > author > name")).extracting(Element::text)
			.containsExactly("alice", "bob", "alice");

		Element firstEntry = requireSelected(feedEl, "> entry");
		assertThat(requireSelected(firstEntry, "> updated").text()).isEqualTo("2026-04-20T10:00:00Z");
		assertThat(requireSelected(firstEntry, "> published").text()).isEqualTo("2026-04-18T08:00:00Z");
		Element summary = requireSelected(firstEntry, "> summary");
		assertThat(summary.attr("type")).isEqualTo("html");
		assertThat(summary.text()).isEqualTo("First summary.");
	}

	@Test
	void rssFeedSetsAtomContentTypeAndCacheHeaders() {
		mockApi.stubGetJson("/entries", FEED_ENTRIES_JSON);

		this.client.get()
			.uri("/rss")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.parseMediaType("application/atom+xml"))
			.expectHeader()
			.value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("max-age=600").contains("public"));
	}

	@Test
	void sitemapEmitsStaticPagesEntriesTagsAndCategories() {
		mockApi.stubGetJson("/entries", SITEMAP_ENTRIES_JSON);
		mockApi.stubGetJson("/tenants/en/entries", SITEMAP_EN_ENTRIES_JSON);
		mockApi.stubGetJson("/tags", """
				[
				  {"name": "Java", "count": 2},
				  {"name": "Spring Boot", "count": 3}
				]
				""");
		mockApi.stubGetJson("/categories", """
				[
				  [{"name": "Programming"}, {"name": "Java"}],
				  [{"name": "Programming"}, {"name": "Spring Framework"}, {"name": "Spring Boot"}]
				]
				""");

		byte[] body = rawBytes("/sitemap.xml");
		Document sitemap = Jsoup.parse(new String(body, StandardCharsets.UTF_8), "", Parser.xmlParser());

		Element urlset = requireSelected(sitemap, "urlset");
		assertThat(urlset.attr("xmlns")).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");

		List<String> locs = urlset.select("> url > loc").stream().map(Element::text).toList();
		// Static pages come first, in a stable order.
		assertThat(locs).startsWith("https://ik.am/", "https://ik.am/entries", "https://ik.am/entries/en",
				"https://ik.am/tags", "https://ik.am/categories", "https://ik.am/aboutme");
		// Entries follow.
		assertThat(locs).contains("https://ik.am/entries/1", "https://ik.am/entries/2");
		// English entry detail pages use the React-parity `/entries/{id}/en` shape and
		// are pulled from the separate `en` upstream tenant.
		assertThat(locs).contains("https://ik.am/entries/101/en", "https://ik.am/entries/102/en");
		// Tag URLs use the task-018 shape with path-encoded spaces.
		assertThat(locs).contains("https://ik.am/tags/Java/entries", "https://ik.am/tags/Spring%20Boot/entries");
		// Category chains use the task-019 shape: single path segment with comma-joined
		// names (matches the React frontend).
		assertThat(locs).contains("https://ik.am/categories/Programming,Java/entries",
				"https://ik.am/categories/Programming,Spring%20Framework,Spring%20Boot/entries");

		// Only entry rows (both tenants) carry <lastmod>; static/tag/category rows do
		// not.
		Element entryUrl = urlset.select("> url")
			.stream()
			.filter(u -> "https://ik.am/entries/1".equals(Objects.requireNonNull(u.selectFirst("loc")).text()))
			.findFirst()
			.orElseThrow();
		assertThat(requireSelected(entryUrl, "lastmod").text()).isEqualTo("2026-04-20T10:00:00Z");
		Element enEntryUrl = urlset.select("> url")
			.stream()
			.filter(u -> "https://ik.am/entries/101/en".equals(Objects.requireNonNull(u.selectFirst("loc")).text()))
			.findFirst()
			.orElseThrow();
		assertThat(requireSelected(enEntryUrl, "lastmod").text()).isEqualTo("2026-04-21T12:00:00Z");
		Element homeUrl = urlset.select("> url")
			.stream()
			.filter(u -> "https://ik.am/".equals(Objects.requireNonNull(u.selectFirst("loc")).text()))
			.findFirst()
			.orElseThrow();
		assertThat(homeUrl.selectFirst("lastmod")).as("no lastmod on static rows").isNull();
	}

	@Test
	void sitemapSetsXmlContentTypeAndCacheHeaders() {
		mockApi.stubGetJson("/entries", SITEMAP_ENTRIES_JSON);
		mockApi.stubGetJson("/tenants/en/entries", EMPTY_ENTRIES_JSON);
		mockApi.stubGetJson("/tags", "[]");
		mockApi.stubGetJson("/categories", "[]");

		this.client.get()
			.uri("/sitemap.xml")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.parseMediaType("application/xml"))
			.expectHeader()
			.value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("max-age=3600").contains("public"));
	}

	@Test
	void sitemapSurvivesEmptyTagsAndCategories() {
		mockApi.stubGetJson("/entries", EMPTY_ENTRIES_JSON);
		mockApi.stubGetJson("/tenants/en/entries", EMPTY_ENTRIES_JSON);
		mockApi.stubGetJson("/tags", "[]");
		mockApi.stubGetJson("/categories", "[]");

		byte[] body = rawBytes("/sitemap.xml");
		Document sitemap = Jsoup.parse(new String(body, StandardCharsets.UTF_8), "", Parser.xmlParser());

		// Static pages are always emitted even without entries / tags / categories.
		List<String> locs = requireSelected(sitemap, "urlset").select("> url > loc")
			.stream()
			.map(Element::text)
			.toList();
		assertThat(locs).containsExactly("https://ik.am/", "https://ik.am/entries", "https://ik.am/entries/en",
				"https://ik.am/tags", "https://ik.am/categories", "https://ik.am/aboutme");
	}

	@Test
	void robotsTxtReferencesSitemapAtSiteBaseUrl() {
		String body = this.client.get()
			.uri("/robots.txt")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
			.expectHeader()
			.value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("max-age=86400").contains("public"))
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isEqualToNormalizingNewlines("""
				User-agent: *
				Allow: /
				Sitemap: https://ik.am/sitemap.xml
				""");
	}

	@Test
	void rssFeedEscapesSpecialCharactersInTitleAndSummary() {
		mockApi.stubGetJson("/entries", """
				{
				  "content": [
				    {
				      "entryId": 7,
				      "tenantId": null,
				      "frontMatter": {
				        "title": "A & B <script>",
				        "summary": "<p>Hi & bye</p>",
				        "categories": [],
				        "tags": []
				      },
				      "content": "",
				      "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
				      "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
				    }
				  ],
				  "size": 30,
				  "hasPrevious": false,
				  "hasNext": false,
				  "nextCursor": null,
				  "previousCursor": null
				}
				""");

		byte[] body = rawBytes("/rss");
		String xml = new String(body, StandardCharsets.UTF_8);
		// Raw ampersand / angle brackets must be XML-escaped in the serialized feed.
		assertThat(xml).contains("A &amp; B &lt;script&gt;");
		assertThat(xml).contains("&lt;p&gt;Hi &amp; bye&lt;/p&gt;");

		Document feed = Jsoup.parse(xml, "", Parser.xmlParser());
		// But after parsing, the text round-trips back to the original values.
		assertThat(requireSelected(feed, "feed > entry > title").text()).isEqualTo("A & B <script>");
		assertThat(requireSelected(feed, "feed > entry > summary").text()).isEqualTo("<p>Hi & bye</p>");
	}

	@Test
	void englishEntriesPagePullsFromEnTenantAndRenders() {
		// Stubbing `/tenants/en/entries` proves the app routes the list request to the
		// English tenant. A stub mismatch here would return 404 and fail the assertion
		// below with an error page.
		mockApi.stubGetJson("/tenants/en/entries", SITEMAP_EN_ENTRIES_JSON);

		Document doc = parsePage("/entries/en", false);

		// <html lang="en"> — screen readers / search engines must see the correct page
		// language.
		assertThat(requireSelected(doc, "html").attr("lang")).isEqualTo("en");
		assertThat(doc.title()).isEqualTo("Entries · IK.AM");
		// Load more button, when rendered, must keep the English base path so
		// pagination stays on the English tenant.
		Element entries = requireSelected(doc, "section#entries");
		assertThat(entries.select("article.entry-card")).hasSize(2);
		assertThat(entries.select("article.entry-card a[href=/entries/101]")).hasSize(1);
	}

	@Test
	void englishEntriesPageReadMoreKeepsEnglishBasePath() {
		mockApi.stubGetJson("/tenants/en/entries", entriesJson(true, "2026-04-05T00:00:00Z"));

		Document doc = parsePage("/entries/en", false);

		// The cumulative URL must preserve `/entries/en` so the next batch is fetched
		// from the English tenant, not the default one.
		assertReadMoreButtonHref(doc, "/entries/en?cursor=2026-04-05T00%3A00%3A00Z&direction=NEXT");
	}

	@Test
	void englishEntryDetailPagePullsFromEnTenantAndRenders() {
		mockApi.stubGetJson("/tenants/en/entries/77", SAMPLE_EN_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/77/en", false);

		assertThat(requireSelected(doc, "html").attr("lang")).isEqualTo("en");
		assertThat(doc.title()).isEqualTo("English Post · IK.AM");

		Element article = requireSelected(doc, "article.entry");
		assertThat(requireSelected(article, "h1.entry-title").text()).isEqualTo("English Post");
		// The MD badge must point at the English raw-markdown path.
		assertThat(requireSelected(article, "a.md-badge").attr("href")).isEqualTo("/entries/77/en.md");
		// Canonical URL includes the `/en` suffix so the English page is not treated as
		// a duplicate of the Japanese one.
		assertThat(requireSelected(doc, "link[rel=canonical]").attr("href")).isEqualTo("https://ik.am/entries/77/en");
		assertThat(requireSelected(doc, "meta[property=og:url]").attr("content"))
			.isEqualTo("https://ik.am/entries/77/en");
	}

	@Test
	void englishEntryDetailReturns404WhenNeitherTenantHasIt() {
		// Both tenants return 404 → plain 404. The "not translated" notice is only
		// shown when the Japanese version exists.
		mockApi.stubGetNotFound("/tenants/en/entries/999");
		mockApi.stubGetNotFound("/entries/999");

		this.client.get().uri("/entries/999/en").exchange().expectStatus().isNotFound();
	}

	@Test
	void englishEntryDetailFallsBackToNotTranslatedNoticeWhenOnlyJapaneseExists() {
		mockApi.stubGetNotFound("/tenants/en/entries/42");
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42/en", false);

		// The response is 200 with a branded notice, not a 404 — readers should have a
		// clear path back to the Japanese original instead of a dead end.
		assertThat(doc.title()).isEqualTo("Not translated yet · IK.AM");
		assertThat(requireSelected(doc, "html").attr("lang")).isEqualTo("en");
		// Reuse the error-page visual shell (centered column, title, body, CTA) so
		// the notice reads as a dedicated page state — not a random alert dropped
		// into the article column.
		Element notice = requireSelected(doc, "section.error-page.not-translated");
		// Globe icon anchors the page visually so "EN / Not Translated" reads as a
		// language state rather than a random pair of labels.
		Element icon = requireSelected(notice, "svg.not-translated-icon");
		assertThat(icon.attr("aria-hidden")).isEqualTo("true");
		assertThat(requireSelected(notice, ".error-status").text()).isEqualTo("EN");
		assertThat(requireSelected(notice, "h1.error-title").text()).isEqualTo("Not Translated");
		// Primary CTA: a two-line button carrying the entry title so readers can
		// confirm they're jumping to the right article.
		Element jaLink = requireSelected(notice, "a.not-translated-ja-link[href=/entries/42]");
		assertThat(requireSelected(jaLink, ".not-translated-ja-title").text()).isEqualTo("Sample Post");
		assertThat(requireSelected(jaLink, ".not-translated-ja-label").text()).isEqualTo("Read the Japanese version");
		// Prefilled GitHub issue — title and body both reference the entry id so the
		// maintainer can triage translation requests quickly. The URL is RFC-3986
		// encoded: spaces become %20, slashes in the body URL stay literal.
		Element issueLink = requireSelected(notice, "a[href*=ik.am_en/issues/new]");
		assertThat(issueLink.attr("href"))
			.isEqualTo("https://github.com/making/ik.am_en/issues/new?title=Translation%20Request%20to%2042"
					+ "&body=Please%20translate%20https://ik.am/entries/42%20into%20English");
		assertThat(issueLink.attr("target")).isEqualTo("_blank");
		assertThat(issueLink.attr("rel")).isEqualTo("noopener");
	}

	@Test
	void notTranslatedNoticeReturnsFragmentForHtmxPartial() {
		mockApi.stubGetNotFound("/tenants/en/entries/42");
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42/en", true);

		// HTMX partial: no layout chrome; just the notice so it swaps in place of the
		// entry body.
		assertIsFragment(doc);
		assertThat(doc.selectFirst("section.error-page.not-translated")).as("not-translated section").isNotNull();
	}

	@Test
	void japaneseEntryDetailIncludesLanguageToggleToEnglishCounterpart() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/42", false);

		// Language switch sits next to the MD badge in the article header. Showing the
		// target language ("EN") keeps the button self-describing without copy.
		Element header = requireSelected(doc, "article.entry .entry-meta");
		Element langBadge = requireSelected(header, "a.lang-badge");
		assertThat(langBadge.text()).isEqualTo("EN");
		assertThat(langBadge.attr("href")).isEqualTo("/entries/42/en");
		assertThat(langBadge.attr("aria-label")).isEqualTo("Switch language");
		// A globe icon is rendered alongside the label so the control reads as a
		// translation toggle rather than an unexplained two-letter badge.
		Element icon = requireSelected(langBadge, "svg.lang-badge-icon");
		assertThat(icon.attr("aria-hidden")).isEqualTo("true");
		// Sibling order: language toggle comes first, MD badge right after.
		Element mdBadge = requireSelected(header, "a.md-badge");
		assertThat(header.children().indexOf(langBadge)).isLessThan(header.children().indexOf(mdBadge));
	}

	@Test
	void englishEntryDetailIncludesLanguageToggleToJapaneseCounterpart() {
		mockApi.stubGetJson("/tenants/en/entries/77", SAMPLE_EN_ENTRY_WITH_CONTENT_JSON);

		Document doc = parsePage("/entries/77/en", false);

		Element langBadge = requireSelected(doc, "article.entry .entry-meta a.lang-badge");
		assertThat(langBadge.text()).isEqualTo("JA");
		assertThat(langBadge.attr("href")).isEqualTo("/entries/77");
	}

	@Test
	void entriesListOmitsLanguageBadgeSinceMdButtonIsAbsent() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/entries", false);

		// The badge is anchored to the MD button in the article header — list pages
		// have no MD button, so no badge either.
		assertThat(doc.selectFirst("a.lang-badge")).as("language badge on list page").isNull();
	}

	@Test
	void englishMarkdownEndpointReturnsRawMarkdown() {
		String markdown = "---\ntitle: English Post\n---\n## Hello EN\n\nSome **bold** body.\n";
		mockApi.stubGet("/tenants/en/entries/77.md", 200, markdown, "text/markdown;charset=UTF-8");

		String body = this.client.get()
			.uri("/entries/77/en.md")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("text/markdown")
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isEqualTo(markdown);
	}

	@Test
	void englishMarkdownEndpointReturns404WhenMissing() {
		mockApi.stubGetNotFound("/tenants/en/entries/99.md");

		this.client.get().uri("/entries/99/en.md").exchange().expectStatus().isNotFound();
	}

	@Test
	void defaultPagesUseJapaneseHtmlLang() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		Document doc = parsePage("/entries", false);

		// Japanese is the primary site language — the React reference sets `lang="ja"`
		// on the root document too.
		assertThat(requireSelected(doc, "html").attr("lang")).isEqualTo("ja");
	}

	@Test
	void upstreamRequestsIncludeBasicAuthHeaderFromProperties() {
		mockApi.stubGetJson("/entries", ENTRIES_JSON_NO_NEXT);

		parsePage("/entries", false);

		// blog-ui:empty — base64(blog-ui:empty) = YmxvZy11aTplbXB0eQ==. The credentials
		// are public (read-only scope on public entries) so they are checked in.
		assertThat(mockApi.lastAuthorizationFor("/entries")).isEqualTo("Basic YmxvZy11aTplbXB0eQ==");
	}

	private void assertSingleSampleEntryCard(Document doc) {
		Element entries = requireSelected(doc, "section#entries");
		assertThat(entries.select("article.entry-card")).hasSize(1);
		Element article = requireSelected(entries, "article.entry-card");
		assertThat(requireSelected(article, "a[href=/entries/42]").text()).isEqualTo("Sample Post");
		assertThat(requireSelected(article, ".entry-card-summary").text()).isEqualTo("A sample summary.");
		assertThat(requireSelected(article, "time").attr("datetime")).isEqualTo("2026-04-10T09:30:00Z");
		assertThat(requireSelected(article, ".entry-card-id").text()).isEqualTo("#00042");
		assertThat(article.select("a.chip")).extracting(e -> e.attr("href"), Element::text)
			.containsExactly(tuple("/tags/Spring%20Boot/entries", "#Spring Boot"));
	}

	private void assertReadMoreButtonHref(Document doc, String expectedHref) {
		Element readMore = requireSelected(doc, "a.read-more-btn");
		assertThat(readMore.attr("href")).isEqualTo(expectedHref);
		// hx-get must mirror href so server- and HTMX-driven navigation land in the same
		// place.
		assertThat(readMore.attr("hx-get")).isEqualTo(expectedHref);
		assertThat(readMore.attr("hx-target")).isEqualTo("closest .more-wrap");
		assertThat(readMore.attr("hx-swap")).isEqualTo("outerHTML");
		assertThat(readMore.text()).isEqualTo("Load more ↓");
	}

	private Document parsePage(String path, boolean htmx) {
		return Jsoup.parse(Objects.requireNonNull(rawBody(path, htmx)));
	}

	private Document parseErrorPage(String path, int expectedStatus) {
		// Spring Boot's error dispatch negotiates on Accept; request HTML explicitly so
		// error.mustache is rendered instead of the default JSON ErrorAttributes body.
		String body = this.client.get()
			.uri(path)
			.accept(MediaType.TEXT_HTML)
			.exchange()
			.expectStatus()
			.isEqualTo(expectedStatus)
			.expectHeader()
			.contentTypeCompatibleWith("text/html")
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		return Jsoup.parse(Objects.requireNonNull(body));
	}

	private static void assertBrandedErrorPage(Document doc, String expectedStatus, String expectedHeadline) {
		// The layout chrome must wrap error pages so users stay in the same shell.
		assertThat(doc.selectFirst("header.site-header")).as("site header").isNotNull();
		assertThat(doc.selectFirst("footer.site-footer")).as("site footer").isNotNull();

		Element errorPage = requireSelected(doc, "section.error-page");
		assertThat(requireSelected(errorPage, "p.error-status").text()).isEqualTo(expectedStatus);
		assertThat(requireSelected(errorPage, "h1.error-title").text()).isEqualTo(expectedHeadline);
		assertThat(requireSelected(errorPage, "a[href=/]").text()).isEqualTo("Go home");
	}

	/**
	 * Asserts the response is a layout-less fragment. {@link Jsoup#parse} synthesizes an
	 * empty {@code <head>} when the input has no document chrome, so we check for that
	 * marker plus the absence of layout-only attributes.
	 */
	private static void assertIsFragment(Document doc) {
		assertThat(doc.head().children()).as("synthesized empty head means no layout chrome").isEmpty();
		assertThat(doc.body().attr("hx-boost")).as("layout's hx-boost attribute should be absent").isEmpty();
	}

	private byte[] rawBytes(String path) {
		byte[] body = this.client.get()
			.uri(path)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(byte[].class)
			.returnResult()
			.getResponseBody();
		return Objects.requireNonNull(body);
	}

	private @Nullable String rawBody(String path, boolean htmx) {
		var spec = this.client.get().uri(path);
		if (htmx) {
			spec = spec.header("HX-Request", "true");
		}
		return spec.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("text/html")
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
	}

	private static Element requireSelected(Element root, String cssQuery) {
		Element element = root.selectFirst(cssQuery);
		assertThat(element).as("element matching %s", cssQuery).isNotNull();
		return Objects.requireNonNull(element);
	}

	private static final String SAMPLE_ENTRY_NO_CONTENT_JSON = """
			{
			  "entryId": 42,
			  "tenantId": null,
			  "frontMatter": {
			    "title": "Sample Post",
			    "summary": "A sample summary.",
			    "categories": [{"name": "Programming"}, {"name": "Java"}],
			    "tags": [{"name": "Spring Boot"}]
			  },
			  "content": "",
			  "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
			  "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
			}
			""";

	private static final String SAMPLE_ENTRY_WITH_CONTENT_JSON = """
			{
			  "entryId": 42,
			  "tenantId": null,
			  "frontMatter": {
			    "title": "Sample Post",
			    "summary": "A sample summary.",
			    "categories": [{"name": "Programming"}],
			    "tags": [{"name": "Spring Boot"}]
			  },
			  "content": "## Hello\\n\\nSome **bold** body.\\n",
			  "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
			  "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
			}
			""";

	private static String staleEntryJson(String updatedIsoInstant) {
		return entryJson(updatedIsoInstant, updatedIsoInstant);
	}

	private static String entryJson(String createdIsoInstant, String updatedIsoInstant) {
		return """
				{
				  "entryId": 42,
				  "tenantId": null,
				  "frontMatter": {
				    "title": "Old Post",
				    "summary": "An older sample summary.",
				    "categories": [{"name": "Programming"}],
				    "tags": [{"name": "Spring Boot"}]
				  },
				  "content": "## Hello\\n\\nSome **bold** body.\\n",
				  "created": {"name": "alice", "date": "%s"},
				  "updated": {"name": "alice", "date": "%s"}
				}
				""".formatted(createdIsoInstant, updatedIsoInstant);
	}

	private static final String SAMPLE_ENTRY_WITH_AMPERSAND_TITLE_JSON = """
			{
			  "entryId": 42,
			  "tenantId": null,
			  "frontMatter": {
			    "title": "A & B?",
			    "summary": "Title with special chars.",
			    "categories": [],
			    "tags": []
			  },
			  "content": "",
			  "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
			  "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
			}
			""";

	private static final String FEED_ENTRIES_JSON = """
			{
			  "content": [
			    {
			      "entryId": 1,
			      "tenantId": null,
			      "frontMatter": {
			        "title": "First Post",
			        "summary": "First summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "alice", "date": "2026-04-18T08:00:00Z"},
			      "updated": {"name": "alice", "date": "2026-04-20T10:00:00Z"}
			    },
			    {
			      "entryId": 2,
			      "tenantId": null,
			      "frontMatter": {
			        "title": "Second Post",
			        "summary": "Second summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "bob", "date": "2026-04-15T12:00:00Z"},
			      "updated": {"name": "bob", "date": "2026-04-17T09:00:00Z"}
			    },
			    {
			      "entryId": 3,
			      "tenantId": null,
			      "frontMatter": {
			        "title": "Third Post",
			        "summary": "Third summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "alice", "date": "2026-04-12T08:00:00Z"},
			      "updated": {"name": "alice", "date": "2026-04-14T11:00:00Z"}
			    }
			  ],
			  "size": 30,
			  "hasPrevious": false,
			  "hasNext": false,
			  "nextCursor": null,
			  "previousCursor": null
			}
			""";

	private static final String SITEMAP_ENTRIES_JSON = """
			{
			  "content": [
			    {
			      "entryId": 1,
			      "tenantId": null,
			      "frontMatter": {
			        "title": "First Post",
			        "summary": "First summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "alice", "date": "2026-04-18T08:00:00Z"},
			      "updated": {"name": "alice", "date": "2026-04-20T10:00:00Z"}
			    },
			    {
			      "entryId": 2,
			      "tenantId": null,
			      "frontMatter": {
			        "title": "Second Post",
			        "summary": "Second summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "bob", "date": "2026-04-15T12:00:00Z"},
			      "updated": {"name": "bob", "date": "2026-04-17T09:00:00Z"}
			    }
			  ],
			  "size": 100,
			  "hasPrevious": false,
			  "hasNext": false,
			  "nextCursor": null,
			  "previousCursor": null
			}
			""";

	private static final String SITEMAP_EN_ENTRIES_JSON = """
			{
			  "content": [
			    {
			      "entryId": 101,
			      "tenantId": "en",
			      "frontMatter": {
			        "title": "First English Post",
			        "summary": "First English summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "alice", "date": "2026-04-19T08:00:00Z"},
			      "updated": {"name": "alice", "date": "2026-04-21T12:00:00Z"}
			    },
			    {
			      "entryId": 102,
			      "tenantId": "en",
			      "frontMatter": {
			        "title": "Second English Post",
			        "summary": "Second English summary.",
			        "categories": [],
			        "tags": []
			      },
			      "content": "",
			      "created": {"name": "bob", "date": "2026-04-10T12:00:00Z"},
			      "updated": {"name": "bob", "date": "2026-04-12T09:00:00Z"}
			    }
			  ],
			  "size": 100,
			  "hasPrevious": false,
			  "hasNext": false,
			  "nextCursor": null,
			  "previousCursor": null
			}
			""";

	private static final String EMPTY_ENTRIES_JSON = """
			{
			  "content": [],
			  "size": 100,
			  "hasPrevious": false,
			  "hasNext": false,
			  "nextCursor": null,
			  "previousCursor": null
			}
			""";

	private static final String SAMPLE_EN_ENTRY_WITH_CONTENT_JSON = """
			{
			  "entryId": 77,
			  "tenantId": "en",
			  "frontMatter": {
			    "title": "English Post",
			    "summary": "An English summary.",
			    "categories": [{"name": "Programming"}],
			    "tags": [{"name": "Spring Boot"}]
			  },
			  "content": "## Hello EN\\n\\nSome **bold** body.\\n",
			  "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
			  "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
			}
			""";

	private static final String ENTRIES_JSON_NO_NEXT = entriesJson(false, null);

	private static String entriesJson(boolean hasNext, @Nullable String nextCursor) {
		return cursorPage(false, hasNext, nextCursor, null, List.of(SAMPLE_ENTRY_NO_CONTENT_JSON));
	}

	private static String cursorPage(boolean hasPrevious, boolean hasNext, @Nullable String nextCursor,
			@Nullable String previousCursor, List<String> entries) {
		String content = String.join(",", entries);
		return """
				{
				  "content": [%s],
				  "size": 20,
				  "hasPrevious": %s,
				  "hasNext": %s,
				  "nextCursor": %s,
				  "previousCursor": %s
				}
				""".formatted(content, hasPrevious, hasNext, jsonStringOrNull(nextCursor),
				jsonStringOrNull(previousCursor));
	}

	private static String jsonStringOrNull(@Nullable String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

}
