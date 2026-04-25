package am.ik.blog.note.web;

import am.ik.blog.testsupport.MockServer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link NoteController}. Uses {@link RestTestClient} against the
 * live Tomcat container (matching the
 * {@link am.ik.blog.counter.web.CounterControllerTests} style) and drives the full Spring
 * Security form-login flow end-to-end — CSRF token extraction, {@code JSESSIONID}
 * handling, and upstream {@code /oauth/token} exchange are all exercised instead of
 * stubbed out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class NoteControllerTests {

	private static final MockServer noteApi = MockServer.start();

	private static final MockServer blogApi = MockServer.start();

	private static final String TOKEN_JSON = """
			{
			  "access_token": "test.jwt.value",
			  "token_type": "Bearer",
			  "expires_in": 3600,
			  "scope": ["SCOPE_note:read"]
			}
			""";

	private static final String NOTES_JSON = """
			[
			  {
			    "entryId": 1,
			    "title": "First note",
			    "noteUrl": "https://note.com/first",
			    "subscribed": true,
			    "updatedDate": "2026-04-01T10:00:00Z"
			  },
			  {
			    "entryId": 2,
			    "title": "Second note",
			    "noteUrl": "https://note.com/second",
			    "subscribed": false,
			    "updatedDate": "2026-04-02T10:00:00Z"
			  }
			]
			""";

	private static final String NOTE_DETAILS_JSON = """
			{
			  "entryId": 1,
			  "content": "# Hello\\n\\nThis is a note.",
			  "frontMatter": { "title": "First note" },
			  "noteUrl": "https://note.com/first",
			  "created": { "name": "making", "date": "2026-03-30T10:00:00Z" },
			  "updated": { "name": "making", "date": "2026-04-01T10:00:00Z" }
			}
			""";

	private static final String NOT_SUBSCRIBED_JSON = """
			{"message":"Not subscribed","noteUrl":"https://note.com/buy"}
			""";

	private static final String SUBSCRIBE_NOTE_ID = "44444444-4444-4444-4444-444444444444";

	/**
	 * Extracts the {@code _csrf} token hidden input value from a server-rendered form.
	 * Matched via regex rather than Jsoup because the login template embeds the token as
	 * a plain {@code <input type="hidden" name="_csrf" value="…">} and regex keeps the
	 * helper free of an additional parser dependency in this file.
	 */
	private static final Pattern CSRF_INPUT = Pattern
		.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"|name=\"_csrf\" value=\"([^\"]+)\"");

	@Autowired
	RestTestClient client;

	@AfterAll
	static void stopServers() {
		noteApi.close();
		blogApi.close();
	}

	@DynamicPropertySource
	static void registerProps(DynamicPropertyRegistry registry) {
		registry.add("note.api.base-url", noteApi::baseUrl);
		// Blog API is not touched by these tests but must resolve at boot.
		registry.add("blog.api.base-url", blogApi::baseUrl);
	}

	@BeforeEach
	void resetStubs() {
		noteApi.reset();
		blogApi.reset();
	}

	@Test
	void unauthenticatedGetNotesRedirectsToLogin() {
		this.client.get()
			.uri("/notes")
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login"));
	}

	@Test
	void unauthenticatedGetNoteDetailRedirectsToLogin() {
		this.client.get()
			.uri("/notes/1")
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login"));
	}

	@Test
	void loginFormRenders() {
		Document doc = getHtml("/note/login");
		assertThat(doc.title()).isEqualTo("Login · IK.AM");
		assertThat(doc.selectFirst("form.note-login-form")).as("login form").isNotNull();
		assertThat(doc.selectFirst("input[name=username]")).isNotNull();
		assertThat(doc.selectFirst("input[name=password]")).isNotNull();
		// Spring Security's CsrfFilter must have embedded a token for the form POST.
		assertThat(requireSelect(doc, "input[name=_csrf]").attr("value")).isNotBlank();
	}

	@Test
	void loginFormShowsErrorOnInvalidCredentials() {
		Document doc = getHtml("/note/login?error");
		assertThat(doc.selectFirst(".note-alert-error")).as("error alert").isNotNull();
	}

	@Test
	void loginSuccessRedirectsToNotes() {
		noteApi.stubPost("/oauth/token", 200, TOKEN_JSON, "application/json");

		LoginPage page = loadLogin();
		this.client.post()
			.uri("/note/login")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(loginBody("alice@example.com", "pw", page.csrfToken()))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/notes"));

		assertThat(noteApi.requestCount("POST", "/oauth/token")).isEqualTo(1);
	}

	@Test
	void loginFailureRedirectsBackWithError() {
		noteApi.stubPost("/oauth/token", 401, "{\"error\":\"unauthorized\"}", "application/json");

		LoginPage page = loadLogin();
		this.client.post()
			.uri("/note/login")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(loginBody("alice@example.com", "wrong", page.csrfToken()))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login?error"));
	}

	@Test
	void authenticatedListRenders() {
		String session = loginAs("alice@example.com");
		noteApi.stubGetJson("/notes", NOTES_JSON);

		Document doc = getHtml("/notes", session);

		assertThat(doc.title()).isEqualTo("はじめるSpring Boot 3 · IK.AM");
		assertThat(requireSelect(doc, ".note-user strong").text()).isEqualTo("alice@example.com");
		assertThat(doc.select(".note-list-item")).hasSize(2);
		assertThat(requireSelect(doc, ".note-counter").text()).isEqualTo("1/2 購読済み");
		// Subscribed row is a link; unsubscribed is not.
		assertThat(requireSelect(doc, ".note-list-item:nth-child(1) .note-list-title a").attr("href"))
			.isEqualTo("/notes/1");
		assertThat(doc.selectFirst(".note-list-item:nth-child(2) .note-list-title a")).isNull();
		// Logout form targets POST /note/logout.
		assertThat(requireSelect(doc, "form.note-logout-form").attr("action")).isEqualTo("/note/logout");
	}

	@Test
	void authenticatedDetailRendersMarkdown() {
		String session = loginAs("alice@example.com");
		noteApi.stubGetJson("/notes/1", NOTE_DETAILS_JSON);

		Document doc = getHtml("/notes/1", session);

		assertThat(requireSelect(doc, "article.note-entry").attr("id")).isEqualTo("note-1");
		assertThat(requireSelect(doc, "h1.entry-title").text()).isEqualTo("First note");
		// Markdown rendered as HTML.
		assertThat(requireSelect(doc, ".entry-body h1").text()).isEqualTo("Hello");
		assertThat(requireSelect(doc, ".entry-body p").text()).isEqualTo("This is a note.");
	}

	@Test
	void unsubscribedDetailRendersNotSubscribedPage() {
		String session = loginAs("alice@example.com");
		noteApi.stubGet("/notes/2", 403, NOT_SUBSCRIBED_JSON, "application/json");

		ExchangeResult result = this.client.get()
			.uri("/notes/2")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.isForbidden()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);

		assertThat(doc.selectFirst(".note-not-subscribed")).isNotNull();
		assertThat(requireSelect(doc, ".note-not-subscribed-message").text()).isEqualTo("Not subscribed");
		assertThat(requireSelect(doc, "a.note-buy-link").attr("href")).isEqualTo("https://note.com/buy");
	}

	@Test
	void detailReturnsNotFoundWhenUpstreamMissing() {
		String session = loginAs("alice@example.com");
		noteApi.stubGetNotFound("/notes/42");

		this.client.get().uri("/notes/42").cookie("JSESSIONID", session).exchange().expectStatus().isNotFound();
	}

	@Test
	void expiredUpstreamTokenSendsUserBackToLogin() {
		String session = loginAs("alice@example.com");
		noteApi.stubGet("/notes", 401, "{}", "application/json");

		this.client.get()
			.uri("/notes")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login?error"));
	}

	@Test
	void logoutRedirectsToLoginPage() {
		String session = loginAs("alice@example.com");
		// Logout is a state-changing POST so it carries a CSRF token. Fetch a fresh
		// one from the notes page rather than re-using the login-page token — CSRF
		// tokens stay valid within a session, but fetching alongside the usual page
		// load mirrors how the browser would submit the logout form.
		noteApi.stubGetJson("/notes", "[]");
		String csrf = requireSelect(getHtml("/notes", session), "form.note-logout-form input[name=_csrf]")
			.attr("value");

		this.client.post()
			.uri("/note/logout")
			.cookie("JSESSIONID", session)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body("_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login?logout"));
	}

	@Test
	void upstreamCallCarriesBearerToken() {
		String session = loginAs("alice@example.com");
		noteApi.stubGetJson("/notes", "[]");

		this.client.get().uri("/notes").cookie("JSESSIONID", session).exchange().expectStatus().isOk();

		assertThat(noteApi.lastAuthorizationFor("/notes")).isEqualTo("Bearer test.jwt.value");
	}

	@Test
	void defaultFilterChainLeavesPublicPagesPermitAll() {
		// Smoke test for the filter-chain scope: the login page must not trigger the
		// authentication entry point (rides the permit-all slot of the note chain).
		this.client.get()
			.uri("/note/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist(HttpHeaders.LOCATION);
	}

	// ---- Subscribe ----

	@Test
	void unauthenticatedSubscribeRedirectsToLogin() {
		this.client.get()
			.uri("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe")
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login"));
	}

	@Test
	void subscribeSuccessRendersNewlySubscribedMessage() {
		String session = loginAs("alice@example.com");
		// Upstream's `subscribed=false` means "was not already subscribed" — i.e. this
		// request flipped the state to subscribed.
		noteApi.stubPost("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe", 200, "{\"entryId\":42,\"subscribed\":false}",
				"application/json");

		ExchangeResult result = this.client.get()
			.uri("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);

		assertThat(doc.title()).isEqualTo("Subscribe · IK.AM");
		assertThat(requireSelect(doc, ".note-alert-success").text()).contains("購読状態になりました");
		assertThat(requireSelect(doc, ".note-alert-success a").attr("href")).isEqualTo("/notes/42");
		assertThat(noteApi.lastAuthorizationFor("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe"))
			.isEqualTo("Bearer test.jwt.value");
	}

	@Test
	void subscribeAlreadySubscribedShowsInfoMessage() {
		String session = loginAs("alice@example.com");
		// Upstream's `subscribed=true` means "was already subscribed" (no state change).
		noteApi.stubPost("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe", 200, "{\"entryId\":42,\"subscribed\":true}",
				"application/json");

		ExchangeResult result = this.client.get()
			.uri("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-info").text()).contains("既に購読状態");
		assertThat(requireSelect(doc, ".note-alert-info a").attr("href")).isEqualTo("/notes/42");
	}

	@Test
	void subscribeUpstreamNotFoundRendersUnknownNoteMessage() {
		String session = loginAs("alice@example.com");
		noteApi.stubPost("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe", 404, "", "application/json");

		ExchangeResult result = this.client.get()
			.uri("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.isNotFound()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-error").text()).contains("存在しないNote");
	}

	@Test
	void subscribeUpstream401SendsUserBackToLogin() {
		String session = loginAs("alice@example.com");
		noteApi.stubPost("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe", 401, "{}", "application/json");

		this.client.get()
			.uri("/notes/" + SUBSCRIBE_NOTE_ID + "/subscribe")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login?error"));
	}

	@Test
	void subscribeRejectsNonUuidPath() {
		String session = loginAs("alice@example.com");
		// Non-UUID noteId should fall through the UUID-constrained route and return 404
		// (no mapping) rather than being swallowed by the subscribe handler.
		this.client.get()
			.uri("/notes/not-a-uuid/subscribe")
			.cookie("JSESSIONID", session)
			.exchange()
			.expectStatus()
			.isNotFound();
	}

	/**
	 * Drives the real form-login flow end-to-end, stubbing the upstream
	 * {@code /oauth/token} to return a canned JWT. Returns the post-login JSESSIONID —
	 * Spring Security's default {@code ChangeSessionIdAuthenticationStrategy} rotates the
	 * session on successful authentication, so callers must use the returned id (not the
	 * pre-login one) for subsequent requests.
	 */
	private String loginAs(String username) {
		noteApi.stubPost("/oauth/token", 200, TOKEN_JSON, "application/json");
		LoginPage page = loadLogin();
		ExchangeResult postResult = this.client.post()
			.uri("/note/login")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(loginBody(username, "pw", page.csrfToken()))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectBody()
			.returnResult();
		return requireCookie(postResult, "JSESSIONID");
	}

	private LoginPage loadLogin() {
		ExchangeResult result = this.client.get()
			.uri("/note/login")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		String jsessionId = requireCookie(result, "JSESSIONID");
		String html = new String(Objects.requireNonNull(result.getResponseBodyContent()), StandardCharsets.UTF_8);
		Matcher matcher = CSRF_INPUT.matcher(html);
		if (!matcher.find()) {
			throw new IllegalStateException("No _csrf token in login form");
		}
		String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
		return new LoginPage(jsessionId, token);
	}

	private static String loginBody(String username, String password, String csrf) {
		return "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password="
				+ URLEncoder.encode(password, StandardCharsets.UTF_8) + "&_csrf="
				+ URLEncoder.encode(csrf, StandardCharsets.UTF_8);
	}

	private Document getHtml(String uri) {
		ExchangeResult result = this.client.get().uri(uri).exchange().expectStatus().isOk().expectBody().returnResult();
		return parseHtml(result);
	}

	private Document getHtml(String uri, String jsessionId) {
		ExchangeResult result = this.client.get()
			.uri(uri)
			.cookie("JSESSIONID", jsessionId)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		return parseHtml(result);
	}

	private static Document parseHtml(ExchangeResult result) {
		byte[] body = result.getResponseBodyContent();
		return Jsoup.parse(new String(body, StandardCharsets.UTF_8));
	}

	private static String requireCookie(ExchangeResult result, String name) {
		ResponseCookie cookie = result.getResponseCookies().getFirst(name);
		return Objects.requireNonNull(cookie, () -> "no " + name + " cookie in response").getValue();
	}

	private static Element requireSelect(Element root, String selector) {
		return Objects.requireNonNull(root.selectFirst(selector), () -> "no match for " + selector);
	}

	private record LoginPage(String jsessionId, String csrfToken) {
	}

}
