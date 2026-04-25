package am.ik.blog.entry;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Giscus (GitHub Discussions) embed configuration. The {@code repo} / {@code repoId} /
 * {@code category} / {@code categoryId} values come from the GitHub App install that
 * backs the conversations — they must match the React frontend's settings so existing
 * threads keep resolving for the same {@code pathname}. The {@code theme} is decided
 * client-side (see {@code giscus.js}) so it tracks the site's light/dark/system toggle
 * rather than the OS scheme alone, which is why it is not a server-side property.
 *
 * <p>
 * Set {@code blog.giscus.enabled=false} to turn the comments embed off entirely. In that
 * case the {@code section.comments} placeholder is not rendered, so {@code giscus.js}
 * finds nothing to hydrate and the Giscus iframe never loads — no network request, no
 * mutation observer. The other fields become optional when disabled.
 */
@ConfigurationProperties(prefix = "blog.giscus")
public record GiscusProps(@DefaultValue("true") boolean enabled, @Nullable String repo, @Nullable String repoId,
		@Nullable String category, @Nullable String categoryId, @DefaultValue("pathname") String mapping) {
}
