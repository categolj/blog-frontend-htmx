package am.ik.blog.entry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Client-side view of a blog entry. Top-level JSON fields {@code entryId} and
 * {@code tenantId} are flattened onto this record (the upstream API unwraps them from
 * {@code EntryKey}).
 */
public record Entry(Long entryId, @Nullable String tenantId, FrontMatter frontMatter, @Nullable String content,
		Author created, Author updated) {

	/**
	 * Explicit constructor so Jackson can deserialize flattened API payloads.
	 */
	@JsonCreator
	public Entry(@JsonProperty("entryId") Long entryId, @JsonProperty("tenantId") @Nullable String tenantId,
			@JsonProperty("frontMatter") FrontMatter frontMatter, @JsonProperty("content") @Nullable String content,
			@JsonProperty("created") Author created, @JsonProperty("updated") Author updated) {
		this.entryId = entryId;
		this.tenantId = tenantId;
		this.frontMatter = frontMatter;
		this.content = content;
		this.created = created;
		this.updated = updated;
	}

	public String formattedId() {
		return "%05d".formatted(this.entryId);
	}

	public String title() {
		return this.frontMatter.title();
	}

	public boolean hasContent() {
		return this.content != null && !this.content.isBlank();
	}

}
