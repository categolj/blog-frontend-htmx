package am.ik.blog.entry.web;

import am.ik.blog.entry.EntryClient;
import am.ik.blog.entry.TagAndCount;
import am.ik.blog.htmx.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TagController {

	/**
	 * Tags change rarely (only when a new entry introduces or retires a tag name), so we
	 * let the browser hold the rendered page for up to an hour before revalidating.
	 */
	private static final CacheControl TAGS_CACHE_CONTROL = CacheControl.maxAge(Duration.ofHours(1))
		.staleWhileRevalidate(Duration.ofMinutes(30));

	private final EntryClient entryClient;

	public TagController(EntryClient entryClient) {
		this.entryClient = entryClient;
	}

	@GetMapping("/tags")
	public String tags(Model model, HttpServletRequest request, HttpServletResponse response) {
		List<TagAndCount> tags = this.entryClient.findAllTags()
			.stream()
			.sorted(Comparator.comparing(TagAndCount::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
		model.addAttribute("tags", tags);
		model.addAttribute("tagCount", tags.size());
		model.addAttribute("pageTitle", "Tags");
		if (Htmx.isPartial(request)) {
			return "fragments/tag-list";
		}
		response.setHeader(HttpHeaders.CACHE_CONTROL, TAGS_CACHE_CONTROL.getHeaderValue());
		response.setHeader(HttpHeaders.VARY, "HX-Request, HX-Boosted");
		return "tags";
	}

}
