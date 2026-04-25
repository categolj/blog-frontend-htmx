package am.ik.blog.config;

import am.ik.blog.note.NoteAuthenticationProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import am.ik.blog.note.NoteApiProps;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NoteApiProps.class)
public class SecurityConfig {

	/**
	 * Authentication scope is intentionally narrow — only the Note feature requires a
	 * signed-in reader. Mounted first (lower order) so its {@code securityMatcher} wins
	 * for paths under {@code /note/**} and {@code /notes(/**)}; every other URL falls
	 * through to {@link #defaultSecurityFilterChain(HttpSecurity)} which stays
	 * permit-all.
	 *
	 * <p>
	 * {@code /note/login} and {@code /note/logout} are the user-facing auth endpoints
	 * (singular), while {@code /notes} and {@code /notes/{entryId}} are the protected
	 * content URLs (plural). CSRF stays enabled here so the login/logout forms get the
	 * usual session-bound token — the default chain below keeps CSRF disabled for the
	 * anonymous read paths.
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain noteSecurityFilterChain(HttpSecurity http, NoteAuthenticationProvider provider)
			throws Exception {
		return http.securityMatcher("/note/**", "/notes", "/notes/**")
			.authenticationProvider(provider)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/note/login", "/note/signup", "/note/password_reset/**", "/note/readers/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			.formLogin(form -> form.loginPage("/note/login")
				.loginProcessingUrl("/note/login")
				.usernameParameter("username")
				.passwordParameter("password")
				// alwaysUseDefaultTargetUrl=false so Spring Security's saved-request
				// mechanism brings the reader back to whatever note URL they tried to
				// open before being bounced to the login page (e.g.
				// /notes/{noteId}/subscribe). Falls back to /notes when there's no
				// saved request (direct visit to /note/login).
				.defaultSuccessUrl("/notes", false)
				.failureUrl("/note/login?error"))
			.logout(logout -> logout.logoutUrl("/note/logout").logoutSuccessUrl("/note/login?logout"))
			.build();
	}

	/**
	 * Anonymous default for every other URL on the site. Matches the prior behaviour —
	 * public entries, tags, categories, feeds, sitemap etc. need no authentication — and
	 * keeps CSRF disabled since none of those endpoints accept state-changing form POSTs
	 * from the browser.
	 */
	@Bean
	@Order(2)
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).csrf(csrf -> csrf.disable()).build();
	}

	/**
	 * Relaxes the default {@link StrictHttpFirewall} to accept percent-encoded slashes
	 * ({@code %2F}) and percent-encoded percents ({@code %25}) in request paths. Tag
	 * names may contain a literal {@code /} (e.g. {@code HTTP/3}), which is emitted as
	 * {@code /tags/HTTP%2F3/entries}; see {@link TomcatConfig} for the matching Tomcat-
	 * side setting. Without this the firewall would reject the request with 400 before
	 * the controller is reached.
	 */
	@Bean
	public HttpFirewall httpFirewall() {
		StrictHttpFirewall firewall = new StrictHttpFirewall();
		firewall.setAllowUrlEncodedSlash(true);
		firewall.setAllowUrlEncodedPercent(true);
		return firewall;
	}

}
