package am.ik.blog.htmx;

/**
 * HTTP header names defined by the HTMX protocol.
 *
 * @see <a href="https://htmx.org/reference/#request_headers">HTMX request headers</a>
 */
public final class HtmxHeaders {

	/**
	 * Always {@code true} when the request is issued by HTMX.
	 */
	public static final String REQUEST = "HX-Request";

	/**
	 * {@code true} when the request originates from a {@code hx-boost} enabled element.
	 */
	public static final String BOOSTED = "HX-Boosted";

	/**
	 * The {@code id} of the target element if it exists.
	 */
	public static final String TARGET = "HX-Target";

	private HtmxHeaders() {
	}

}
