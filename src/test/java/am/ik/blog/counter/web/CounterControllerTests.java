package am.ik.blog.counter.web;

import am.ik.blog.testsupport.MockServer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CounterController}. The external Counter API is stubbed
 * via {@link MockServer} so the real {@code RestClient} + Jackson decoding path stays
 * under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class CounterControllerTests {

	// RFC 5737 documentation-only address — never a real host.
	private static final String BLACKLIST_IP = "192.0.2.1";

	private static final MockServer counterApi = MockServer.start();

	private static final MockServer blogApi = MockServer.start();

	@Autowired
	RestTestClient client;

	@AfterAll
	static void stopServers() {
		counterApi.close();
		blogApi.close();
	}

	@DynamicPropertySource
	static void registerProps(DynamicPropertyRegistry registry) {
		registry.add("counter.api.base-url", counterApi::baseUrl);
		registry.add("counter.api.ip-black-list[0]", () -> BLACKLIST_IP);
		// The main Blog API is not touched by these tests but must resolve at boot.
		registry.add("blog.api.base-url", blogApi::baseUrl);
	}

	@BeforeEach
	void resetStubs() {
		counterApi.reset();
		blogApi.reset();
	}

	@Test
	void incrementsAndRendersCounterFragment() {
		counterApi.stubPostJson("/counter", "{\"counter\":123}");

		Document doc = fetchCounter(42L, null, null);

		Element span = requireSelect(doc, "span.views-counter");
		assertThat(requireSelect(span, ".views-count").text()).isEqualTo("123");
		assertThat(requireSelect(span, ".views-label").text()).isEqualTo("Views");
		assertThat(counterApi.requestCount("POST", "/counter")).isEqualTo(1);
		assertThat(counterApi.requestCount("POST", "/counter?readOnly=true")).isZero();
	}

	@Test
	void botUserAgentReturnsZeroWithoutCallingUpstream() {
		Document doc = fetchCounter(42L, "Googlebot/2.1", null);

		assertThat(requireSelect(doc, "span.views-counter .views-count").text()).isEqualTo("0");
		assertThat(counterApi.requestCount("POST", "/counter")).isZero();
		assertThat(counterApi.requestCount("POST", "/counter?readOnly=true")).isZero();
	}

	@Test
	void blacklistedIpUsesReadOnlyUpstream() {
		counterApi.stubPostJson("/counter?readOnly=true", "{\"counter\":999}");

		Document doc = fetchCounter(42L, null, BLACKLIST_IP);

		assertThat(requireSelect(doc, "span.views-counter .views-count").text()).isEqualTo("999");
		assertThat(counterApi.requestCount("POST", "/counter?readOnly=true")).isEqualTo(1);
		assertThat(counterApi.requestCount("POST", "/counter")).isZero();
	}

	@Test
	void blacklistedIpExtractsFirstForwardedForEntry() {
		counterApi.stubPostJson("/counter?readOnly=true", "{\"counter\":5}");

		// Proxy chain — only the client-most entry matters for blacklist matching.
		Document doc = fetchCounter(42L, null, BLACKLIST_IP + ", 10.0.0.1");

		assertThat(requireSelect(doc, "span.views-counter .views-count").text()).isEqualTo("5");
		assertThat(counterApi.requestCount("POST", "/counter?readOnly=true")).isEqualTo(1);
	}

	@Test
	void emptyUpstreamResponseRendersPlaceholder() {
		counterApi.stubPostJson("/counter", "");

		Document doc = fetchCounter(42L, null, null);

		assertThat(requireSelect(doc, "span.views-counter .views-count").text()).isEqualTo("—");
	}

	private Document fetchCounter(long entryId, @Nullable String userAgent, @Nullable String forwardedFor) {
		RestTestClient.RequestHeadersSpec<?> spec = this.client.post().uri("/counter/{entryId}", entryId);
		if (userAgent != null) {
			spec = spec.header(HttpHeaders.USER_AGENT, userAgent);
		}
		if (forwardedFor != null) {
			spec = spec.header("X-Forwarded-For", forwardedFor);
		}
		byte[] body = spec.exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
		return Jsoup.parse(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
	}

	private static Element requireSelect(Element root, String selector) {
		return Objects.requireNonNull(root.selectFirst(selector), () -> "no match for " + selector);
	}

}
