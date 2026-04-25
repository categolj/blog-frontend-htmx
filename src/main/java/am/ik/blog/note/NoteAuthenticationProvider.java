package am.ik.blog.note;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Delegates form-login credentials to the upstream Note API's {@code /oauth/token}
 * endpoint. On success, mints a {@link NoteAuthentication} carrying the JWT and the
 * scopes the upstream granted (e.g. {@code SCOPE_note:read}).
 *
 * <p>
 * The provider is wired into the note-scoped {@code SecurityFilterChain} only — unrelated
 * parts of the site keep their anonymous access and never see this provider.
 */
@Component
public class NoteAuthenticationProvider implements AuthenticationProvider {

	private final NoteClient noteClient;

	private final InstantSource instantSource;

	public NoteAuthenticationProvider(NoteClient noteClient, InstantSource instantSource) {
		this.noteClient = noteClient;
		this.instantSource = instantSource;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String username = authentication.getName();
		Object credentials = authentication.getCredentials();
		if (username == null || credentials == null) {
			throw new BadCredentialsException("Missing credentials");
		}
		String password = credentials.toString();
		OAuth2Token token;
		try {
			token = this.noteClient.issueToken(username, password);
		}
		catch (RestClientException e) {
			throw new BadCredentialsException("Authentication failed", e);
		}
		Instant issuedAt = this.instantSource.instant();
		Instant expiresAt = issuedAt.plusSeconds(token.expiresIn());
		List<GrantedAuthority> authorities = token.scope()
			.stream()
			.<GrantedAuthority>map(SimpleGrantedAuthority::new)
			.toList();
		return NoteAuthentication.builder()
			.username(username)
			.accessToken(token.accessToken())
			.expiresAt(expiresAt)
			.authorities(authorities)
			.build();
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
