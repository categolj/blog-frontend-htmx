package am.ik.blog.entry;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import am.ik.blog.testsupport.MockServer;
import am.ik.spring.http.client.circuitbreaker.CircuitBreaker;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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

/**
 * Integration tests for {@link CachingEntryClient}. The upstream Blog API is stubbed at
 * the HTTP layer via {@link MockServer}; a mutable {@link InstantSource} lets tests
 * expire cache TTLs deterministically without sleeping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(CachingEntryClientTests.MutableClockConfig.class)
class CachingEntryClientTests {

	@TestConfiguration
	static class MutableClockConfig {

		private static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.now());

		@Bean
		@Primary
		InstantSource testInstantSource() {
			return NOW::get;
		}

		static void advance(Duration duration) {
			NOW.updateAndGet(now -> now.plus(duration));
		}

		static void reset() {
			NOW.set(Instant.now());
		}

	}

	// Started eagerly so its baseUrl() is available when @DynamicPropertySource fires.
	private static final MockServer mockApi = MockServer.start();

	@Autowired
	RestTestClient client;

	@Autowired
	EntryCache entryCache;

	@Autowired
	CircuitBreakerRegistry circuitBreakerRegistry;

	@Autowired
	MeterRegistry meterRegistry;

	@AfterAll
	static void stopMockApi() {
		mockApi.close();
	}

	@DynamicPropertySource
	static void registerProps(DynamicPropertyRegistry registry) {
		registry.add("blog.api.base-url", mockApi::baseUrl);
	}

	@BeforeEach
	void resetState() {
		mockApi.reset();
		this.entryCache.clear();
		this.circuitBreakerRegistry.circuitBreakers().forEach(CircuitBreaker::reset);
		MutableClockConfig.reset();
	}

	@Test
	void successResponsesAreCachedWithinTtl() {
		mockApi.stubGetJson("/entries", entriesJson("Cached List Entry"));
		mockApi.stubGetJson("/entries/42", entryJson("Cached Detail Entry"));

		for (int i = 0; i < 2; i++) {
			Document doc = parsePage("/");
			assertThat(requireSelected(doc, "article.entry-card a[href=/entries/42]").text())
				.isEqualTo("Cached List Entry");
		}
		assertThat(mockApi.requestCount("GET", "/entries?size=20")).as("list served from cache").isEqualTo(1);

		for (int i = 0; i < 2; i++) {
			Document doc = parsePage("/entries/42");
			assertThat(requireSelected(doc, "h1.entry-title").text()).isEqualTo("Cached Detail Entry");
		}
		assertThat(mockApi.requestCount("GET", "/entries/42")).as("detail served from cache").isEqualTo(1);

		assertThat(this.meterRegistry.find("blog.entry.cache").tags("op", "list", "result", "fresh").counter())
			.as("cache outcomes are observed via Micrometer")
			.isNotNull();
	}

	@Test
	void expiredCacheIsServedWhileUpstreamIsDown() {
		// Prime every cached operation while upstream is healthy.
		mockApi.stubGetJson("/entries", entriesJson("Cached List Entry"));
		mockApi.stubGetJson("/entries/42", entryJson("Cached Detail Entry"));
		mockApi.stubGetJson("/entries/42.md", "# Cached Markdown");
		mockApi.stubGetJson("/tags", tagsJson());
		parsePage("/");
		parsePage("/entries/42");
		fetchMarkdown();
		parsePage("/tags");

		// Expire every TTL and take the upstream down.
		MutableClockConfig.advance(Duration.ofHours(2));
		mockApi.reset();
		mockApi.stubGet("/entries", 500, "upstream down", "application/json");
		mockApi.stubGet("/entries/42", 500, "upstream down", "application/json");
		mockApi.stubGet("/entries/42.md", 500, "upstream down", "application/json");
		mockApi.stubGet("/tags", 500, "upstream down", "application/json");

		Document list = parsePage("/");
		assertThat(requireSelected(list, "article.entry-card a[href=/entries/42]").text())
			.isEqualTo("Cached List Entry");
		Document detail = parsePage("/entries/42");
		assertThat(requireSelected(detail, "h1.entry-title").text()).isEqualTo("Cached Detail Entry");
		assertThat(fetchMarkdown()).isEqualTo("# Cached Markdown");
		parsePage("/tags");
		assertThat(requireSelected(parsePage("/tags"), "a[href=\"/tags/Cached%20Tag/entries\"]")).isNotNull();

		// Each operation attempted exactly one upstream refresh before falling back.
		assertThat(mockApi.requestCount("GET", "/entries?size=20")).isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42")).isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42.md")).isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/tags")).isEqualTo(1);

		// A conditional request must not error while upstream is down: headEntry reports
		// the failure as a non-304 and the page falls through to the cached full render.
		String ifModifiedSince = DateTimeFormatter.RFC_1123_DATE_TIME
			.format(Instant.parse("2026-04-10T09:30:00Z").atZone(ZoneOffset.UTC));
		String conditional = this.client.get()
			.uri("/entries/42")
			.header(HttpHeaders.IF_MODIFIED_SINCE, ifModifiedSince)
			.accept(MediaType.TEXT_HTML)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(conditional).contains("Cached Detail Entry");
	}

	@Test
	void upstreamFailureWithoutCachedValuePropagates() {
		mockApi.stubGet("/entries", 500, "upstream down", "application/json");

		this.client.get().uri("/").accept(MediaType.TEXT_HTML).exchange().expectStatus().is5xxServerError();
	}

	@Test
	void notFoundIsCachedAsNegativeEntry() {
		mockApi.stubGetNotFound("/entries/99");

		this.client.get().uri("/entries/99").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound();
		this.client.get().uri("/entries/99").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound();
		assertThat(mockApi.requestCount("GET", "/entries/99")).as("404 served from the negative cache").isEqualTo(1);

		// Expired negative entry + upstream outage still answers 404 from the cache.
		MutableClockConfig.advance(Duration.ofMinutes(10));
		mockApi.reset();
		mockApi.stubGet("/entries/99", 500, "upstream down", "application/json");
		this.client.get().uri("/entries/99").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound();
		assertThat(mockApi.requestCount("GET", "/entries/99")).as("one upstream refresh, then stale negative")
			.isEqualTo(1);
	}

	@Test
	void openBreakerFailsFastAndServesStaleCache() {
		mockApi.stubGetJson("/entries", entriesJson("Cached List Entry"));
		parsePage("/");

		MutableClockConfig.advance(Duration.ofMinutes(2));
		mockApi.reset();
		this.circuitBreakerRegistry.circuitBreaker("127.0.0.1").transitionToOpen();

		Document doc = parsePage("/");
		assertThat(requireSelected(doc, "article.entry-card a[href=/entries/42]").text())
			.isEqualTo("Cached List Entry");
		assertThat(mockApi.requestCount("GET", "/entries?size=20")).as("open breaker rejects without touching upstream")
			.isZero();
	}

	@Test
	void recoveredUpstreamServesFreshDataAfterTtlExpiry() {
		mockApi.stubGetJson("/entries", entriesJson("Old Title"));
		parsePage("/");

		// Expire the list TTL, then restore upstream with new content.
		MutableClockConfig.advance(Duration.ofMinutes(2));
		mockApi.reset();
		mockApi.stubGetJson("/entries", entriesJson("New Title"));

		Document doc = parsePage("/");
		assertThat(requireSelected(doc, "article.entry-card a[href=/entries/42]").text()).isEqualTo("New Title");
		assertThat(mockApi.requestCount("GET", "/entries?size=20")).isEqualTo(1);
	}

	private Document parsePage(String path) {
		String body = this.client.get()
			.uri(path)
			.accept(MediaType.TEXT_HTML)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_HTML)
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		return Jsoup.parse(Objects.requireNonNull(body));
	}

	private String fetchMarkdown() {
		String body = this.client.get()
			.uri("/entries/42.md")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		return Objects.requireNonNull(body);
	}

	private static Element requireSelected(Element root, String cssQuery) {
		Element element = root.selectFirst(cssQuery);
		assertThat(element).as("element matching %s", cssQuery).isNotNull();
		return Objects.requireNonNull(element);
	}

	private static String entriesJson(String title) {
		return """
				{
				  "content": [{
				    "entryId": 42,
				    "tenantId": null,
				    "frontMatter": {"title": "%s", "summary": "", "categories": [], "tags": []},
				    "content": "",
				    "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
				    "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
				  }],
				  "size": 20,
				  "hasPrevious": false,
				  "hasNext": false,
				  "nextCursor": null,
				  "previousCursor": null
				}
				""".formatted(title);
	}

	private static String entryJson(String title) {
		return """
				{
				  "entryId": 42,
				  "tenantId": null,
				  "frontMatter": {"title": "%s", "summary": "", "categories": [], "tags": []},
				  "content": "## Body",
				  "created": {"name": "alice", "date": "2026-04-10T09:30:00Z"},
				  "updated": {"name": "alice", "date": "2026-04-10T09:30:00Z"}
				}
				""".formatted(title);
	}

	private static String tagsJson() {
		return """
				[{"name": "Cached Tag", "count": 2}]
				""";
	}

}
