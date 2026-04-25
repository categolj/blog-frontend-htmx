package am.ik.blog.note;

/**
 * Raised when a call to the upstream Note API is rejected with 401 after the user has
 * already authenticated on this app. Typical cause: the upstream JWT (12h lifetime)
 * expired while the Spring Security session (longer lifetime) is still valid. Callers
 * should invalidate the session and send the user back through the login flow.
 */
public class NoteSessionExpiredException extends RuntimeException {

	public NoteSessionExpiredException(String message) {
		super(message);
	}

}
