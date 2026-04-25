package am.ik.blog.note;

import am.ik.blog.entry.Author;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Response shape of {@code GET /notes/{entryId}}. Only served to readers who are
 * subscribed — unsubscribed callers get a 403 with an {@link NoteErrorResponse} body
 * instead.
 *
 * <p>
 * {@link Author} is reused from the entry feature since the JSON shape ({@code name} +
 * ISO-8601 date) is identical. {@code @JsonInclude(NON_EMPTY)} matches the upstream so
 * round-tripping through the record does not introduce spurious nulls.
 */
@JsonInclude(Include.NON_EMPTY)
public record NoteDetails(Long entryId, String content, NoteFrontMatter frontMatter, String noteUrl, Author created,
		Author updated) {

	public String title() {
		return this.frontMatter.title();
	}
}
