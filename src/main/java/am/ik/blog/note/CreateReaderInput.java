package am.ik.blog.note;

/**
 * Request body shape for {@code POST /readers}. {@code rawPassword} (not
 * {@code password}) is the upstream contract — the upstream hashes it server-side.
 */
public record CreateReaderInput(String email, String rawPassword) {
}
