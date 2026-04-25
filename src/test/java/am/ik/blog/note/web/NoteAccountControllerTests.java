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
 * End-to-end tests for {@link NoteAccountController}. Exercises the full HTTP flow (CSRF
 * token extraction, session cookies, form POSTs, redirect-follow) via
 * {@link RestTestClient} against a real Tomcat container, with the upstream Note API
 * stubbed through {@link MockServer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class NoteAccountControllerTests {

	private static final MockServer noteApi = MockServer.start();

	private static final MockServer blogApi = MockServer.start();

	private static final String READER_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ACTIVATION_LINK_ID = "22222222-2222-2222-2222-222222222222";

	private static final String RESET_ID = "33333333-3333-3333-3333-333333333333";

	/** See {@link NoteControllerTests#CSRF_INPUT} — same parsing idiom. */
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

	// ---- Signup ----

	@Test
	void signupFormRenders() {
		Document doc = getHtml("/note/signup");
		assertThat(doc.title()).isEqualTo("Sign up · IK.AM");
		assertThat(doc.selectFirst("form")).isNotNull();
		assertThat(doc.selectFirst("input[name=email]")).isNotNull();
		assertThat(doc.selectFirst("input[name=password]")).isNotNull();
		assertThat(doc.selectFirst("input[name=confirmPassword]")).isNotNull();
		assertThat(requireSelect(doc, "input[name=_csrf]").attr("value")).isNotBlank();
	}

	@Test
	void signupSuccessRedirectsWithSignedUpFlag() {
		noteApi.stubPost("/readers", 200, "{\"message\":\"Sent an activation link to alice@example.com\"}",
				"application/json");

		FormPage page = loadForm("/note/signup");
		this.client.post()
			.uri("/note/signup")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(signupBody("alice@example.com", "pw", "pw", page.csrfToken()))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/signup?signedUp"));

		assertThat(noteApi.requestCount("POST", "/readers")).isEqualTo(1);
	}

	@Test
	void signupFollowedRedirectShowsSuccessMessage() {
		// Confirms the PRG landing page renders the success banner.
		Document doc = getHtml("/note/signup?signedUp");
		assertThat(doc.selectFirst(".note-alert-success")).isNotNull();
	}

	@Test
	void signupPasswordMismatchShowsError() {
		FormPage page = loadForm("/note/signup");
		ExchangeResult result = this.client.post()
			.uri("/note/signup")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(signupBody("alice@example.com", "pw1", "pw2", page.csrfToken()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-error").text()).contains("パスワード");
		// Preserves the typed email across the re-render.
		assertThat(requireSelect(doc, "input[name=email]").attr("value")).isEqualTo("alice@example.com");
		assertThat(noteApi.requestCount("POST", "/readers")).isZero();
	}

	@Test
	void signupUpstreamErrorRendersMessageFromUpstream() {
		noteApi.stubPost("/readers", 400, "{\"message\":\"Email already registered\"}", "application/json");

		FormPage page = loadForm("/note/signup");
		ExchangeResult result = this.client.post()
			.uri("/note/signup")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(signupBody("dup@example.com", "pw", "pw", page.csrfToken()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-error").text()).isEqualTo("Email already registered");
		assertThat(requireSelect(doc, "input[name=email]").attr("value")).isEqualTo("dup@example.com");
	}

	// ---- Password reset: request link ----

	@Test
	void passwordResetRequestFormRenders() {
		Document doc = getHtml("/note/password_reset/send_link");
		assertThat(doc.title()).isEqualTo("Password reset · IK.AM");
		assertThat(requireSelect(doc, "input[name=email]")).isNotNull();
	}

	@Test
	void passwordResetRequestSuccessRedirects() {
		noteApi.stubPost("/password_reset/send_link", 200, "{\"message\":\"Sent a link.\"}", "application/json");

		FormPage page = loadForm("/note/password_reset/send_link");
		this.client.post()
			.uri("/note/password_reset/send_link")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(emailBody("alice@example.com", page.csrfToken()))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION,
					location -> assertThat(location).endsWith("/note/password_reset/send_link?sent"));
	}

	@Test
	void passwordResetRequestUpstream404RendersError() {
		noteApi.stubPost("/password_reset/send_link", 404, "{\"message\":\"Reader not found\"}", "application/json");

		FormPage page = loadForm("/note/password_reset/send_link");
		ExchangeResult result = this.client.post()
			.uri("/note/password_reset/send_link")
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(emailBody("unknown@example.com", page.csrfToken()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-error").text()).isEqualTo("Reader not found");
	}

	// ---- Password reset: apply ----

	@Test
	void passwordResetFormRenders() {
		Document doc = getHtml("/note/password_reset/" + RESET_ID);
		assertThat(doc.title()).isEqualTo("Password reset · IK.AM");
		assertThat(requireSelect(doc, "form.note-login-form").attr("action"))
			.isEqualTo("/note/password_reset/" + RESET_ID);
		assertThat(requireSelect(doc, "input[name=password]")).isNotNull();
		assertThat(requireSelect(doc, "input[name=confirmPassword]")).isNotNull();
	}

	@Test
	void passwordResetSuccessRedirectsToLogin() {
		noteApi.stubPost("/password_reset", 200, "{\"message\":\"Reset the password\"}", "application/json");

		FormPage page = loadForm("/note/password_reset/" + RESET_ID);
		this.client.post()
			.uri("/note/password_reset/" + RESET_ID)
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(resetBody("newpw", "newpw", page.csrfToken()))
			.exchange()
			.expectStatus()
			.is3xxRedirection()
			.expectHeader()
			.value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/note/login?reset"));
	}

	@Test
	void passwordResetMismatchShowsError() {
		FormPage page = loadForm("/note/password_reset/" + RESET_ID);
		ExchangeResult result = this.client.post()
			.uri("/note/password_reset/" + RESET_ID)
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(resetBody("a", "b", page.csrfToken()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-error").text()).contains("パスワード");
		assertThat(noteApi.requestCount("POST", "/password_reset")).isZero();
	}

	@Test
	void passwordResetExpiredLinkShowsUpstreamMessage() {
		noteApi.stubPost("/password_reset", 400, "{\"message\":\"The given link has been already expired.\"}",
				"application/json");

		FormPage page = loadForm("/note/password_reset/" + RESET_ID);
		ExchangeResult result = this.client.post()
			.uri("/note/password_reset/" + RESET_ID)
			.cookie("JSESSIONID", page.jsessionId())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(resetBody("pw", "pw", page.csrfToken()))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.returnResult();
		Document doc = parseHtml(result);
		assertThat(requireSelect(doc, ".note-alert-error").text()).contains("expired");
	}

	@Test
	void passwordResetRejectsNonUuidPath() {
		// "send_link" is literal; any other non-UUID path should 404 from Spring's
		// path-pattern matcher rather than being swallowed by the reset handler.
		this.client.get().uri("/note/password_reset/not-a-uuid").exchange().expectStatus().isNotFound();
	}

	// ---- Activation ----

	@Test
	void activationSuccessRendersSuccessMessage() {
		noteApi.stubPost("/readers/" + READER_ID + "/activations/" + ACTIVATION_LINK_ID, 200,
				"{\"message\":\"Activated " + READER_ID + "\"}", "application/json");

		Document doc = getHtml("/note/readers/" + READER_ID + "/activations/" + ACTIVATION_LINK_ID);
		assertThat(doc.title()).isEqualTo("Activation · IK.AM");
		assertThat(doc.selectFirst(".note-alert-success")).isNotNull();
	}

	@Test
	void activationFailureRendersUpstreamMessage() {
		noteApi.stubPost("/readers/" + READER_ID + "/activations/" + ACTIVATION_LINK_ID, 400,
				"{\"message\":\"The given link has been already expired.\"}", "application/json");

		Document doc = getHtml("/note/readers/" + READER_ID + "/activations/" + ACTIVATION_LINK_ID);
		assertThat(requireSelect(doc, ".note-alert-error").text()).contains("expired");
	}

	// ---- Login page reflects reset redirect ----

	@Test
	void loginPageShowsPasswordResetBannerWhenFlagged() {
		Document doc = getHtml("/note/login?reset");
		assertThat(doc.selectFirst(".note-alert-success")).isNotNull();
	}

	// ---- Helpers ----

	private FormPage loadForm(String uri) {
		ExchangeResult result = this.client.get().uri(uri).exchange().expectStatus().isOk().expectBody().returnResult();
		String jsessionId = requireCookie(result, "JSESSIONID");
		byte[] body = result.getResponseBodyContent();
		String html = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
		Matcher matcher = CSRF_INPUT.matcher(html);
		if (!matcher.find()) {
			throw new IllegalStateException("No _csrf token in form at " + uri);
		}
		String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
		return new FormPage(jsessionId, token);
	}

	private Document getHtml(String uri) {
		ExchangeResult result = this.client.get().uri(uri).exchange().expectStatus().isOk().expectBody().returnResult();
		return parseHtml(result);
	}

	private static Document parseHtml(ExchangeResult result) {
		byte[] body = result.getResponseBodyContent();
		return Jsoup.parse(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
	}

	private static String signupBody(String email, String password, String confirmPassword, String csrf) {
		return "email=" + enc(email) + "&password=" + enc(password) + "&confirmPassword=" + enc(confirmPassword)
				+ "&_csrf=" + enc(csrf);
	}

	private static String emailBody(String email, String csrf) {
		return "email=" + enc(email) + "&_csrf=" + enc(csrf);
	}

	private static String resetBody(String password, String confirmPassword, String csrf) {
		return "password=" + enc(password) + "&confirmPassword=" + enc(confirmPassword) + "&_csrf=" + enc(csrf);
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String requireCookie(ExchangeResult result, String name) {
		ResponseCookie cookie = result.getResponseCookies().getFirst(name);
		return Objects.requireNonNull(cookie, () -> "no " + name + " cookie in response").getValue();
	}

	private static Element requireSelect(Element root, String selector) {
		return Objects.requireNonNull(root.selectFirst(selector), () -> "no match for " + selector);
	}

	private record FormPage(String jsessionId, String csrfToken) {
	}

}
