package am.ik.blog.note;

import org.jspecify.annotations.Nullable;

/**
 * Body returned by the Note API for 403 responses on {@code GET /notes/{entryId}} — the
 * reader is authenticated but has not subscribed to the note. The upstream includes the
 * external {@code noteUrl} (note.com link) so the UI can offer a "buy" entry point.
 */
public record NoteErrorResponse(@Nullable String message, @Nullable String noteUrl) {
}
