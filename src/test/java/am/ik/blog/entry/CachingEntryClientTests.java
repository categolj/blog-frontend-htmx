package am.ik.blog.entry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CachingEntryClient}. The upstream Blog API is stubbed at
 * the HTTP layer via {@link MockServer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class CachingEntryClientTests {

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
	}

	@Test
	void healthyUpstreamIsAskedOnEveryRequest() {
		mockApi.stubGetJson("/entries", entriesJson("Old Title"));
		mockApi.stubGetJson("/entries/42", entryJson("Old Title"));
		parsePage("/");
		parsePage("/entries/42");

		// Upstream changes right away: the stored values must not stand in for it.
		mockApi.reset();
		mockApi.stubGetJson("/entries", entriesJson("New Title"));
		mockApi.stubGetJson("/entries/42", entryJson("New Title"));

		Document list = parsePage("/");
		assertThat(requireSelected(list, "article.entry-card a[href=/entries/42]").text()).isEqualTo("New Title");
		Document detail = parsePage("/entries/42");
		assertThat(requireSelected(detail, "h1.entry-title").text()).isEqualTo("New Title");
		assertThat(mockApi.requestCount("GET", "/entries?size=20")).isEqualTo(1);
		assertThat(mockApi.requestCount("GET", "/entries/42")).isEqualTo(1);
	}

	@Test
	void storedValueIsServedWhileUpstreamIsDown() {
		// Prime every cached operation while upstream is healthy.
		mockApi.stubGetJson("/entries", entriesJson("Cached List Entry"));
		mockApi.stubGetJson("/entries/42", entryJson("Cached Detail Entry"));
		mockApi.stubGetJson("/entries/42.md", "# Cached Markdown");
		mockApi.stubGetJson("/tags", tagsJson());
		parsePage("/");
		parsePage("/entries/42");
		fetchMarkdown();
		parsePage("/tags");

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
		assertThat(requireSelected(parsePage("/tags"), "a[href=\"/tags/Cached%20Tag/entries\"]")).isNotNull();

		// Each operation attempted upstream exactly once before falling back.
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

		assertThat(this.meterRegistry.find("blog.entry.cache").tags("op", "list", "result", "stale").counter())
			.as("fallback outcomes are observed via Micrometer")
			.isNotNull();
	}

	@Test
	void upstreamFailureWithoutCachedValuePropagates() {
		mockApi.stubGet("/entries", 500, "upstream down", "application/json");

		this.client.get().uri("/").accept(MediaType.TEXT_HTML).exchange().expectStatus().is5xxServerError();
	}

	@Test
	void notFoundDoesNotOverwriteStoredValue() {
		mockApi.stubGetJson("/entries/42", entryJson("Stored Entry"));
		parsePage("/entries/42");

		// A 404 is not evidence about the resource: while upstream is degraded a gateway
		// can answer 404 for a backend that is merely down. It is passed through, but
		// storing it would replace the entry the fallback depends on.
		mockApi.reset();
		mockApi.stubGetNotFound("/entries/42");
		this.client.get().uri("/entries/42").accept(MediaType.TEXT_HTML).exchange().expectStatus().isNotFound();

		mockApi.reset();
		mockApi.stubGet("/entries/42", 500, "upstream down", "application/json");
		Document doc = parsePage("/entries/42");
		assertThat(requireSelected(doc, "h1.entry-title").text()).isEqualTo("Stored Entry");
	}

	@Test
	void openBreakerFailsFastAndServesStaleCache() {
		mockApi.stubGetJson("/entries", entriesJson("Cached List Entry"));
		parsePage("/");

		mockApi.reset();
		this.circuitBreakerRegistry.circuitBreaker("127.0.0.1").transitionToOpen();

		Document doc = parsePage("/");
		assertThat(requireSelected(doc, "article.entry-card a[href=/entries/42]").text())
			.isEqualTo("Cached List Entry");
		assertThat(mockApi.requestCount("GET", "/entries?size=20")).as("open breaker rejects without touching upstream")
			.isZero();
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
