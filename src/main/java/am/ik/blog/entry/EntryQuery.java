package am.ik.blog.entry;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * Query parameters for listing entries.
 */
public record EntryQuery(@Nullable String query, @Nullable String tag, @Nullable List<String> categories,
		@Nullable String cursor, int size, PageDirection direction) {

	public static final int DEFAULT_SIZE = 20;

	/** Pagination direction understood by the upstream API. */
	public enum PageDirection {

		NEXT, PREVIOUS

	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		@Nullable private String query;

		@Nullable private String tag;

		@Nullable private List<String> categories;

		@Nullable private String cursor;

		private int size = DEFAULT_SIZE;

		private PageDirection direction = PageDirection.NEXT;

		private Builder() {
		}

		public Builder query(@Nullable String query) {
			this.query = query;
			return this;
		}

		public Builder tag(@Nullable String tag) {
			this.tag = tag;
			return this;
		}

		public Builder categories(@Nullable List<String> categories) {
			this.categories = categories;
			return this;
		}

		public Builder cursor(@Nullable String cursor) {
			this.cursor = cursor;
			return this;
		}

		public Builder size(int size) {
			Assert.isTrue(size > 0, "size must be positive");
			this.size = size;
			return this;
		}

		public Builder direction(PageDirection direction) {
			this.direction = direction;
			return this;
		}

		public EntryQuery build() {
			return new EntryQuery(query, tag, categories, cursor, size, direction);
		}

	}

}
