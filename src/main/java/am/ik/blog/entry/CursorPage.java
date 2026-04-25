package am.ik.blog.entry;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Cursor-based page response, mirrors the API's {@code CursorPage} shape.
 */
public record CursorPage<T>(List<T> content, int size, boolean hasPrevious, boolean hasNext,
		@Nullable String nextCursor, @Nullable String previousCursor) {

	public CursorPage {
		content = content == null ? List.of() : List.copyOf(content);
	}

	public boolean isEmpty() {
		return this.content.isEmpty();
	}

}
