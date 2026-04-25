package am.ik.blog.entry.web;

import am.ik.blog.entry.Category;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * View-model for a single link in the entry-detail breadcrumb row. {@code url} is the
 * task-019 hierarchical URL for the chain prefix ending at this crumb. {@code last}
 * drives the template's separator rendering.
 */
public record CategoryCrumb(String name, String url, boolean last) {

	/**
	 * Builds cumulative breadcrumb links for the category hierarchy. Each crumb's
	 * {@code url} covers the prefix from the root up to and including that crumb, so
	 * clicking an intermediate crumb lands on the narrower-scoped listing.
	 */
	public static List<CategoryCrumb> fromChain(List<Category> categories) {
		List<CategoryCrumb> crumbs = new ArrayList<>(categories.size());
		List<String> accum = new ArrayList<>(categories.size());
		for (int i = 0; i < categories.size(); i++) {
			accum.add(categories.get(i).name());
			crumbs.add(new CategoryCrumb(categories.get(i).name(), landingUrl(accum), i == categories.size() - 1));
		}
		return crumbs;
	}

	/**
	 * Wraps the {@link CategoryUrl} segment into the full
	 * {@code /categories/{chain}/entries} URL. {@link UriComponentsBuilder#pathSegment}
	 * percent-encodes spaces to {@code %20} while leaving sub-delims like {@code ,}
	 * literal, which is exactly what we want for the comma-delimited chain.
	 */
	private static String landingUrl(List<String> chain) {
		return UriComponentsBuilder.fromPath("/categories")
			.pathSegment(CategoryUrl.of(chain))
			.path("/entries")
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();
	}

}
