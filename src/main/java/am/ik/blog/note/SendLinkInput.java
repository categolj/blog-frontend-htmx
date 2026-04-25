package am.ik.blog.note;

/**
 * Request body shape for {@code POST /password_reset/send_link}.
 */
public record SendLinkInput(String email) {
}
