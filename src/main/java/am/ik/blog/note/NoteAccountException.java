package am.ik.blog.note;

/**
 * Raised when the upstream Note API rejects an account-management request (signup,
 * activation, password reset) with a 4xx response that carries a user-facing message in a
 * {@link ResponseMessage} body. The controller surfaces {@link #getMessage()} directly to
 * the reader.
 */
public class NoteAccountException extends RuntimeException {

	public NoteAccountException(String message) {
		super(message);
	}

}
