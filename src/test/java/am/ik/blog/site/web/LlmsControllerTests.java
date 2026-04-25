package am.ik.blog.site.web;

import am.ik.blog.testsupport.MockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link LlmsController}. The upstream Blog API is stubbed at the
 * HTTP layer via {@link MockServer} so the real {@code RestClient} + Jackson decoding
 * path stays under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class LlmsControllerTests {

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
	void llmsTxtRendersOverviewWithJapaneseAndEnglishEntries() {
		mockApi.stubGetJson("/entries", entriesJson(100, "Japanese Entry", "2024-04-02T00:00:00Z"));
		mockApi.stubGetJson("/tenants/en/entries", entriesJson(200, "English Entry", "2024-04-03T00:00:00Z"));

		String body = this.client.get()
			.uri("/llms.txt")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isNotNull();
		// Overview header / nav block is literal — verify they survive text-block
		// rewrites.
		assertThat(body).contains("# IK.AM");
		// Tagline is sourced from `blog.description` (FeedController also uses it as the
		// Atom subtitle) — tests pin the default so accidental removal surfaces here too.
		assertThat(body).contains("@making's tech note");
		assertThat(body).contains("## ナビゲーション");
		assertThat(body).contains("- [記事一覧](/entries.md)");
		assertThat(body).contains("- [記事一覧 (English)](/entries/en.md)");
		// Japanese entries use `/entries/{id}.md`; English ones use
		// `/entries/{id}/en.md`.
		assertThat(body).contains("- [Japanese Entry](/entries/100.md) - 最終更新時刻 2024-04-02T00:00:00Z");
		assertThat(body).contains("- [English Entry](/entries/200/en.md) - Last Updated at 2024-04-03T00:00:00Z");
	}

	@Test
	void entriesMdListsJapaneseEntriesWithMoreLink() {
		mockApi.stubGetJson("/entries", entriesJson(42, "Sample Post", "2026-04-10T09:30:00Z"));

		String body = this.client.get()
			.uri("/entries.md")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isNotNull();
		assertThat(body).contains("# IK.AM");
		assertThat(body).contains("## Entries");
		assertThat(body).contains("- [Sample Post](/entries/42.md) - Last Updated at 2026-04-10T09:30:00Z");
		// "More Entries" cursor is the last entry's updated date so a crawler can page.
		assertThat(body).contains("[More Entries](/entries.md?cursor=2026-04-10T09:30:00Z)");
	}

	@Test
	void entriesMdForTenantRoutesToTenantEndpointAndLinksIntoTenantPaths() {
		mockApi.stubGetJson("/tenants/en/entries", entriesJson(77, "English Post", "2026-04-05T00:00:00Z"));

		String body = this.client.get()
			.uri("/entries/en.md")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isNotNull();
		// Entry links and the More Entries cursor URL must include the tenant segment so
		// crawlers stay on the English tenant while paging.
		assertThat(body).contains("- [English Post](/entries/77/en.md) - Last Updated at 2026-04-05T00:00:00Z");
		assertThat(body).contains("[More Entries](/entries/en.md?cursor=2026-04-05T00:00:00Z)");
	}

	@Test
	void entriesMdEmitsEmptyMoreLinkWhenNoEntries() {
		mockApi.stubGetJson("/entries", """
				{
				  "content": [],
				  "size": 20,
				  "hasPrevious": false,
				  "hasNext": false,
				  "nextCursor": null,
				  "previousCursor": null
				}
				""");

		String body = this.client.get()
			.uri("/entries.md")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isNotNull();
		// Empty list must not fabricate a cursor URL pointing at nothing.
		assertThat(body).contains("[More Entries]()");
	}

	private static String entriesJson(long entryId, String title, String updatedInstant) {
		return """
				{
				  "content": [
				    {
				      "entryId": %d,
				      "tenantId": null,
				      "frontMatter": {
				        "title": "%s",
				        "summary": "",
				        "categories": [],
				        "tags": []
				      },
				      "content": "",
				      "created": {"name": "alice", "date": "%s"},
				      "updated": {"name": "alice", "date": "%s"}
				    }
				  ],
				  "size": 20,
				  "hasPrevious": false,
				  "hasNext": false,
				  "nextCursor": null,
				  "previousCursor": null
				}
				""".formatted(entryId, title, updatedInstant, updatedInstant);
	}

}
