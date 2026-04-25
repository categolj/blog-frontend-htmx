package am.ik.blog.note;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

/**
 * Response body shape of the upstream {@code POST /oauth/token} endpoint.
 *
 * <p>
 * Upstream field names use snake_case (matching the OAuth2 token-response RFC), so the
 * record exposes camelCase accessors via explicit {@link JsonProperty} bindings.
 */
public record OAuth2Token(@JsonProperty("access_token") String accessToken,
		@JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") long expiresIn, Set<String> scope) {
}
