package am.ik.blog.entry;

import am.ik.blog.testsupport.MockServer;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code blog.giscus.enabled=false} fully removes the Giscus embed so no
 * comments placeholder is rendered and {@code giscus.js} has nothing to hydrate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@TestPropertySource(properties = "blog.giscus.enabled=false")
class GiscusDisabledTests {

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
	void entryDetailOmitsGiscusSectionWhenDisabled() {
		mockApi.stubGetJson("/entries/42", SAMPLE_ENTRY_JSON);

		byte[] body = this.client.get()
			.uri("/entries/42")
			.accept(MediaType.TEXT_HTML)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult()
			.getResponseBody();
		Document doc = Jsoup.parse(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));

		assertThat(doc.selectFirst("article.entry")).as("article should still render when Giscus is disabled")
			.isNotNull();
		assertThat(doc.select("section.comments"))
			.as("section.comments should not be rendered when blog.giscus.enabled=false")
			.isEmpty();
	}

	private static final String SAMPLE_ENTRY_JSON = """
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

}
