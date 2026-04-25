package am.ik.blog.note;

import org.jspecify.annotations.Nullable;

/**
 * Raised when the upstream Note API returns 403 for a detail request. The caller is
 * authenticated but has not subscribed to the note; {@code noteUrl} points at the
 * note.com page where the reader can purchase access.
 */
public class NoteNotSubscribedException extends RuntimeException {

	@Nullable private final String noteUrl;

	public NoteNotSubscribedException(String message, @Nullable String noteUrl) {
		super(message);
		this.noteUrl = noteUrl;
	}

	@Nullable public String noteUrl() {
		return this.noteUrl;
	}

}
