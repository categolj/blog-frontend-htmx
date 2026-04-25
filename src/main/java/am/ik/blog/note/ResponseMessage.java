package am.ik.blog.note;

/**
 * Generic {@code {"message": "..."}} envelope returned by the Note API's account
 * endpoints (signup, activation, password reset). Used for both 2xx success bodies and
 * 4xx error bodies.
 */
public record ResponseMessage(String message) {
}
