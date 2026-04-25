package am.ik.blog.note;

/**
 * Front-matter block embedded in a {@link NoteDetails} response.
 *
 * <p>
 * The Note API only exposes the title — categories/tags/summary that the regular entry
 * API emits are not part of the note model, so this record stays intentionally narrower
 * than {@link am.ik.blog.entry.FrontMatter}.
 */
public record NoteFrontMatter(String title) {
}
