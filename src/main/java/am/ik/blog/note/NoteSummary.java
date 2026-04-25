package am.ik.blog.note;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * List entry shape returned by {@code GET /notes}. The upstream {@code noteId} JSON field
 * is suppressed by the server when rendering for regular readers, so it is not modeled
 * here.
 */
public record NoteSummary(Long entryId, @Nullable String title, String noteUrl, boolean subscribed,
		@Nullable Instant updatedDate) {
}
