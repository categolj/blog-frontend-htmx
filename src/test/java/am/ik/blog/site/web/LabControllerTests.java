package am.ik.blog.site.web;

import am.ik.blog.testsupport.MockServer;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class LabControllerTests {

	// Started eagerly so its baseUrl() is available when @DynamicPropertySource fires.
	// /lab itself does not touch the upstream API, but BlogFrontendHtmxApplication wires
	// in EntryClient unconditionally, so it must still resolve to a reachable URL.
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

	@Test
	void labIndexListsMovToGifTool() {
		Document doc = fetch("/lab");

		assertThat(doc.title()).isEqualTo("Lab · IK.AM");
		Element title = doc.selectFirst(".hero-title");
		assertThat(title).isNotNull();
		assertThat(title.text()).isEqualTo("Lab");

		Element toolLink = doc.selectFirst(".lab-tool-title a");
		assertThat(toolLink).isNotNull();
		assertThat(toolLink.attr("href")).isEqualTo("/lab/mov-to-gif");
		assertThat(toolLink.text()).isEqualTo("MOV to GIF Converter");
		// Full reload so the browser evaluates the converter's module script and loads
		// assets fresh — hx-boost's innerHTML swap would skip those.
		assertThat(toolLink.attr("hx-boost")).isEqualTo("false");
	}

	@Test
	void movToGifPageRendersConverterScaffold() {
		Document doc = fetch("/lab/mov-to-gif");

		assertThat(doc.title()).isEqualTo("MOV to GIF Converter · IK.AM");

		Element root = doc.selectFirst("#mov-to-gif");
		assertThat(root).as("mov-to-gif root container").isNotNull();
		assertThat(root.selectFirst("[data-dropzone]")).isNotNull();
		assertThat(root.selectFirst("[data-file-input]")).isNotNull();
		assertThat(root.selectFirst("[data-convert]")).isNotNull();
		assertThat(root.selectFirst("[data-result]")).isNotNull();
		assertThat(root.selectFirst("[data-log]")).isNotNull();

		// Script tag referenced by src — Spring's ResourceUrlProvider may version the
		// URL, so only assert the path contains mov-to-gif.
		Element script = doc.selectFirst("script[src*=mov-to-gif]");
		assertThat(script).as("mov-to-gif.js include").isNotNull();
		assertThat(script.attr("src")).endsWith(".js");
	}

	private Document fetch(String path) {
		byte[] body = this.client.get()
			.uri(path)
			.accept(MediaType.TEXT_HTML)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.contentTypeCompatibleWith("text/html")
			.expectBody()
			.returnResult()
			.getResponseBody();
		return Jsoup.parse(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
	}

}
