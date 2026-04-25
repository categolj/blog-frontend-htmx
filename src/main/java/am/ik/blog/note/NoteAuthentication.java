package am.ik.blog.note;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication token minted by {@link NoteAuthenticationProvider} once the upstream
 * Note API returns a JWT for the submitted credentials.
 *
 * <p>
 * The principal is the reader's email (used as the OAuth2 {@code username}); the JWT is
 * kept in a dedicated field rather than {@code credentials} so Spring Security's
 * {@code ProviderManager#eraseCredentials = true} (the default) does not clear it after
 * authentication completes. Controllers pull the token via {@link #getAccessToken()} to
 * forward to the upstream on each read.
 *
 * <p>
 * Constructed via {@link #builder()} — direct instantiation is blocked to keep the
 * four-field argument list off the call site (per the project's "more than two arguments
 * => Builder" rule).
 */
public final class NoteAuthentication extends AbstractAuthenticationToken {

	private final String username;

	private final String accessToken;

	private final Instant expiresAt;

	private NoteAuthentication(Builder builder) {
		super(builder.authorities);
		this.username = Objects.requireNonNull(builder.username, "username");
		this.accessToken = Objects.requireNonNull(builder.accessToken, "accessToken");
		this.expiresAt = Objects.requireNonNull(builder.expiresAt, "expiresAt");
		super.setAuthenticated(true);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Object getPrincipal() {
		return this.username;
	}

	@Override
	public Object getCredentials() {
		return "";
	}

	@Override
	public String getName() {
		return this.username;
	}

	public String getAccessToken() {
		return this.accessToken;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public static final class Builder {

		@Nullable private String username;

		@Nullable private String accessToken;

		@Nullable private Instant expiresAt;

		private Collection<? extends GrantedAuthority> authorities = List.of();

		private Builder() {
		}

		public Builder username(String username) {
			this.username = username;
			return this;
		}

		public Builder accessToken(String accessToken) {
			this.accessToken = accessToken;
			return this;
		}

		public Builder expiresAt(Instant expiresAt) {
			this.expiresAt = expiresAt;
			return this;
		}

		public Builder authorities(Collection<? extends GrantedAuthority> authorities) {
			this.authorities = authorities;
			return this;
		}

		public NoteAuthentication build() {
			return new NoteAuthentication(this);
		}

	}

}
