package am.ik.blog.entry;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record FrontMatter(String title, @Nullable String summary, List<Category> categories, List<Tag> tags) {

	public FrontMatter {
		summary = Objects.requireNonNullElse(summary, "");
		categories = Objects.requireNonNullElseGet(categories, List::of);
		tags = Objects.requireNonNullElseGet(tags, List::of);
	}

	public boolean hasSummary() {
		return this.summary != null && !this.summary.isBlank();
	}

	public boolean hasTags() {
		return !this.tags.isEmpty();
	}

	public boolean hasCategories() {
		return !this.categories.isEmpty();
	}

}
