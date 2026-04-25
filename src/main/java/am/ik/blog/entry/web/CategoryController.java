package am.ik.blog.entry.web;

import am.ik.blog.entry.Category;
import am.ik.blog.entry.EntryClient;
import am.ik.blog.htmx.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CategoryController {

	/**
	 * Categories change even less often than tags, but we keep the same policy as the
	 * tags page so the two landing pages share a consistent freshness story.
	 */
	private static final CacheControl CATEGORIES_CACHE_CONTROL = CacheControl.maxAge(Duration.ofHours(1))
		.staleWhileRevalidate(Duration.ofMinutes(30));

	private final EntryClient entryClient;

	public CategoryController(EntryClient entryClient) {
		this.entryClient = entryClient;
	}

	@GetMapping("/categories")
	public String categories(Model model, HttpServletRequest request, HttpServletResponse response) {
		List<List<Category>> raw = this.entryClient.findAllCategories();
		List<List<CategoryCrumb>> categories = raw.stream()
			.filter(chain -> !chain.isEmpty())
			.map(CategoryCrumb::fromChain)
			.toList();
		model.addAttribute("categories", categories);
		model.addAttribute("hasCategories", !categories.isEmpty());
		model.addAttribute("pageTitle", "Categories");
		if (Htmx.isPartial(request)) {
			return "fragments/category-list";
		}
		response.setHeader(HttpHeaders.CACHE_CONTROL, CATEGORIES_CACHE_CONTROL.getHeaderValue());
		response.setHeader(HttpHeaders.VARY, "HX-Request, HX-Boosted");
		return "categories";
	}

}
