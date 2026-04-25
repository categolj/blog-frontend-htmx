package am.ik.blog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Site-wide configuration used to build absolute URLs and social-share metadata
 * (canonical link, OGP, Twitter Card). {@code xHandle} doubles as the
 * {@code twitter:site} value — the Twitter Card spec predates the X rebrand, but the
 * attribute name is unchanged and the value is still an {@code @handle}.
 *
 * <p>
 * Intentionally placed at the root package rather than under any feature package: it is
 * depended on by multiple features ({@code entry} detail pages, {@code site}
 * feed/sitemap/robots), so putting it inside a feature slice would create cross-feature
 * cycles. It is not in {@code config} either, because {@code config} is the orchestration
 * layer that depends on features — not the other way round.
 */
@ConfigurationProperties(prefix = "blog")
public record BlogProps(@DefaultValue("https://ik.am") String baseUrl,
		@DefaultValue("https://raw.githubusercontent.com/categolj/blog-frontend-old/master/blog-frontend-ui/public/ms-icon-310x310.png") String defaultOgImage,
		@DefaultValue("IK.AM") String name, @DefaultValue("@making's tech note") String description,
		@DefaultValue("@making") String xHandle) {

	/**
	 * Derives the X profile URL from {@link #xHandle} so callers don't have to configure
	 * it separately. Strips the leading {@code @} before joining.
	 */
	public String xUrl() {
		String handle = this.xHandle.startsWith("@") ? this.xHandle.substring(1) : this.xHandle;
		return "https://x.com/" + handle;
	}

}
