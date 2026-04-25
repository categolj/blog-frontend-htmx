package am.ik.blog.note.web;

import am.ik.blog.note.NoteSummary;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * View-model projection of {@link NoteSummary} for the note-list template.
 *
 * <p>
 * Pre-computes {@code detailHref} (only subscribed rows link to the detail page) and a
 * pre-formatted ISO-8601 timestamp so the template stays free of Mustache lambdas. Split
 * out of the controller and built via {@link #builder()} to comply with the "more than
 * two arguments => Builder" standard.
 */
public final class NoteListItem {

	private final Long entryId;

	private final String title;

	private final String noteUrl;

	private final boolean subscribed;

	@Nullable private final String updatedIso;

	@Nullable private final String detailHref;

	private NoteListItem(Builder builder) {
		this.entryId = Objects.requireNonNull(builder.entryId, "entryId");
		this.title = builder.title == null ? "" : builder.title;
		this.noteUrl = Objects.requireNonNull(builder.noteUrl, "noteUrl");
		this.subscribed = builder.subscribed;
		this.updatedIso = builder.updatedIso;
		this.detailHref = builder.detailHref;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Adapts the upstream DTO into a ready-to-render view-model. Subscribed rows get a
	 * link target to the detail page; unsubscribed rows leave {@code detailHref} null so
	 * the template renders a plain label instead of a link.
	 */
	public static NoteListItem from(NoteSummary summary) {
		String href = summary.subscribed() ? "/notes/" + summary.entryId() : null;
		String iso = summary.updatedDate() == null ? null : summary.updatedDate().toString();
		return builder().entryId(summary.entryId())
			.title(summary.title())
			.noteUrl(summary.noteUrl())
			.subscribed(summary.subscribed())
			.updatedIso(iso)
			.detailHref(href)
			.build();
	}

	public Long entryId() {
		return this.entryId;
	}

	public String title() {
		return this.title;
	}

	public String noteUrl() {
		return this.noteUrl;
	}

	public boolean subscribed() {
		return this.subscribed;
	}

	@Nullable public String updatedIso() {
		return this.updatedIso;
	}

	@Nullable public String detailHref() {
		return this.detailHref;
	}

	public boolean hasTitle() {
		return !this.title.isBlank();
	}

	public static final class Builder {

		@Nullable private Long entryId;

		@Nullable private String title;

		@Nullable private String noteUrl;

		private boolean subscribed;

		@Nullable private String updatedIso;

		@Nullable private String detailHref;

		private Builder() {
		}

		public Builder entryId(Long entryId) {
			this.entryId = entryId;
			return this;
		}

		public Builder title(@Nullable String title) {
			this.title = title;
			return this;
		}

		public Builder noteUrl(String noteUrl) {
			this.noteUrl = noteUrl;
			return this;
		}

		public Builder subscribed(boolean subscribed) {
			this.subscribed = subscribed;
			return this;
		}

		public Builder updatedIso(@Nullable String updatedIso) {
			this.updatedIso = updatedIso;
			return this;
		}

		public Builder detailHref(@Nullable String detailHref) {
			this.detailHref = detailHref;
			return this;
		}

		public NoteListItem build() {
			return new NoteListItem(this);
		}

	}

}
