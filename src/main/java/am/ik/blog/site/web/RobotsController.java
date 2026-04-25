package am.ik.blog.site.web;

import am.ik.blog.BlogProps;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves {@code /robots.txt}. Rendered dynamically so the {@code Sitemap:} directive
 * points at the configured {@code blog.base-url} (staging vs. production differ).
 */
@Controller
public class RobotsController {

	private final BlogProps blogProps;

	public RobotsController(BlogProps blogProps) {
		this.blogProps = blogProps;
	}

	@GetMapping(path = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> robots() {
		String baseUrl = stripTrailingSlash(this.blogProps.baseUrl());
		String body = """
				User-agent: *
				Allow: /
				Sitemap: %s/sitemap.xml
				""".formatted(baseUrl);
		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_PLAIN)
			.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
			.body(body);
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
