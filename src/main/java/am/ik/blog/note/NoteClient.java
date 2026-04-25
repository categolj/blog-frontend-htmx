package am.ik.blog.note;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Thin REST client over the upstream Note API.
 *
 * <p>
 * Authentication is per-call rather than client-scoped: each read/write accepts a JWT
 * argument and attaches it as a Bearer credential. Tokens are issued by
 * {@link #issueToken(String, String)} after a successful login exchange with the upstream
 * and stored in the user's Spring Security authentication.
 */
@Component
public class NoteClient {

	private static final ParameterizedTypeReference<List<NoteSummary>> SUMMARY_LIST = new ParameterizedTypeReference<>() {
	};

	private final RestClient restClient;

	public NoteClient(RestClient.Builder builder, NoteApiProps props) {
		this.restClient = builder.baseUrl(props.baseUrl())
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	/**
	 * Exchanges username/password for a JWT via {@code POST /oauth/token}.
	 *
	 * <p>
	 * Translates a 401 into {@link BadCredentialsException} so the Spring Security form
	 * login infrastructure reports the failure through its normal failure handler; any
	 * other upstream error bubbles up as the original {@code RestClientException}.
	 */
	public OAuth2Token issueToken(String username, String password) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", username);
		form.add("password", password);
		try {
			OAuth2Token token = this.restClient.post()
				.uri("/oauth/token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(OAuth2Token.class);
			return Objects.requireNonNull(token, "token response body");
		}
		catch (HttpClientErrorException.Unauthorized e) {
			throw new BadCredentialsException("Invalid username or password", e);
		}
	}

	public List<NoteSummary> findAll(String accessToken) {
		try {
			List<NoteSummary> notes = this.restClient.get()
				.uri("/notes")
				.headers(headers -> headers.setBearerAuth(accessToken))
				.retrieve()
				.body(SUMMARY_LIST);
			return notes == null ? List.of() : notes;
		}
		catch (HttpClientErrorException.Unauthorized e) {
			throw new NoteSessionExpiredException("Upstream rejected the session token");
		}
	}

	/**
	 * Fetches a single note for the authenticated reader. Returns {@link Optional#empty}
	 * on 404; throws {@link NoteNotSubscribedException} on 403 so the controller can
	 * render a "not subscribed" page with the {@code noteUrl} link; throws
	 * {@link NoteSessionExpiredException} on 401 so the caller can force re-login.
	 *
	 * <p>
	 * 404 is handled via {@code onStatus} (swallowing the error) so the HTTP client span
	 * stays successful in tracing — a missing note is a normal outcome, not a fault. 403
	 * and 401 are translated from the {@link HttpClientErrorException} subclasses thrown
	 * by {@code retrieve().body()} so {@link HttpClientErrorException#getResponseBodyAs}
	 * can deserialise the error body using the configured message converters.
	 */
	public Optional<NoteDetails> findByEntryId(Long entryId, String accessToken) {
		try {
			NoteDetails details = this.restClient.get()
				.uri("/notes/{entryId}", entryId)
				.headers(headers -> headers.setBearerAuth(accessToken))
				.retrieve()
				.onStatus(HttpStatus.NOT_FOUND::isSameCodeAs, (request, response) -> {
					// Swallow: translated into Optional.empty() below.
				})
				.body(NoteDetails.class);
			return Optional.ofNullable(details);
		}
		catch (HttpClientErrorException.Forbidden e) {
			NoteErrorResponse error = e.getResponseBodyAs(NoteErrorResponse.class);
			String message = error == null || error.message() == null ? "Not subscribed" : error.message();
			String noteUrl = error == null ? null : error.noteUrl();
			throw new NoteNotSubscribedException(message, noteUrl);
		}
		catch (HttpClientErrorException.Unauthorized e) {
			throw new NoteSessionExpiredException("Upstream rejected the session token");
		}
	}

	/**
	 * Registers a new reader account. The upstream emails an activation link; the account
	 * stays inactive until the link is visited via
	 * {@link #activateReader(String, String)}. Upstream 4xx bodies are surfaced through
	 * {@link NoteAccountException} so the form can display the server's message verbatim.
	 */
	public ResponseMessage createReader(String email, String rawPassword) {
		try {
			return requireNonNull(this.restClient.post()
				.uri("/readers")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new CreateReaderInput(email, rawPassword))
				.retrieve()
				.body(ResponseMessage.class));
		}
		catch (HttpClientErrorException e) {
			throw accountException(e);
		}
	}

	/**
	 * Consumes an activation link to flip a reader from pending to active. Returns the
	 * upstream response (typically {@code "Activated …"}) so the landing page can echo
	 * it. Expired links come back as a 400; unknown links as a 404 — both translate to
	 * {@link NoteAccountException}.
	 */
	public ResponseMessage activateReader(String readerId, String activationLinkId) {
		try {
			return requireNonNull(this.restClient.post()
				.uri("/readers/{readerId}/activations/{activationLinkId}", readerId, activationLinkId)
				.retrieve()
				.body(ResponseMessage.class));
		}
		catch (HttpClientErrorException e) {
			throw accountException(e);
		}
	}

	/**
	 * Requests a password-reset email for the given address. The upstream returns 404
	 * when no reader matches the email — surface as {@link NoteAccountException} so the
	 * form renders the upstream's message (prevents leaking which addresses are
	 * registered through the HTTP status code alone).
	 */
	public ResponseMessage sendPasswordResetLink(String email) {
		try {
			return requireNonNull(this.restClient.post()
				.uri("/password_reset/send_link")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new SendLinkInput(email))
				.retrieve()
				.body(ResponseMessage.class));
		}
		catch (HttpClientErrorException e) {
			throw accountException(e);
		}
	}

	/**
	 * Completes a password reset using the one-time {@code resetId} from the email link.
	 * Expired or unknown reset ids surface as {@link NoteAccountException} so the form
	 * can prompt the reader to request a new link.
	 */
	public ResponseMessage resetPassword(UUID resetId, String newPassword) {
		try {
			return requireNonNull(this.restClient.post()
				.uri("/password_reset")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new PasswordResetInput(resetId, newPassword))
				.retrieve()
				.body(ResponseMessage.class));
		}
		catch (HttpClientErrorException e) {
			throw accountException(e);
		}
	}

	/**
	 * Subscribes the authenticated reader to the note identified by {@code noteId}.
	 * Returns {@link Optional#empty()} if the upstream reports the note does not exist;
	 * throws {@link NoteSessionExpiredException} when the stored JWT has been rejected so
	 * the controller can send the reader back through login.
	 *
	 * <p>
	 * The upstream response's {@code subscribed} flag means <i>was already subscribed</i>
	 * (see {@link SubscribeOutput}). Callers decide whether to treat the outcome as
	 * "newly subscribed" or "already subscribed" for the reader-facing message.
	 */
	public Optional<SubscribeOutput> subscribe(UUID noteId, String accessToken) {
		try {
			SubscribeOutput output = this.restClient.post()
				.uri("/notes/{noteId}/subscribe", noteId)
				.headers(headers -> headers.setBearerAuth(accessToken))
				.retrieve()
				.onStatus(HttpStatus.NOT_FOUND::isSameCodeAs, (request, response) -> {
					// Swallow: translated into Optional.empty() below.
				})
				.body(SubscribeOutput.class);
			return Optional.ofNullable(output);
		}
		catch (HttpClientErrorException.Unauthorized e) {
			throw new NoteSessionExpiredException("Upstream rejected the session token");
		}
	}

	private static <T> T requireNonNull(@Nullable T value) {
		return Objects.requireNonNull(value, "response body");
	}

	/**
	 * Extracts a user-facing message from a 4xx response. Account endpoints consistently
	 * return {@link ResponseMessage} bodies, so decode with Spring's built-in
	 * {@link HttpClientErrorException#getResponseBodyAs}; fall back to the reason phrase
	 * if the body is missing or malformed.
	 */
	private static NoteAccountException accountException(HttpClientErrorException e) {
		ResponseMessage body = e.getResponseBodyAs(ResponseMessage.class);
		String message = body == null || body.message() == null ? e.getStatusText() : body.message();
		return new NoteAccountException(message);
	}

}
