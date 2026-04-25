package am.ik.blog.entry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the upstream Blog API.
 *
 * <p>
 * {@code username}/{@code password} are sent as HTTP Basic credentials. The default
 * values correspond to a read-only public account and carry no privileged access, so they
 * are checked in as a convenience — override per-environment when needed.
 */
@ConfigurationProperties(prefix = "blog.api")
public record EntryApiProps(@DefaultValue("http://localhost:8080") String baseUrl, @DefaultValue("") String username,
		@DefaultValue("") String password) {
}
