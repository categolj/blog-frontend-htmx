package am.ik.blog.htmx;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Tiny helper around HTMX request headers.
 */
public final class Htmx {

	private Htmx() {
	}

	public static boolean isHtmxRequest(HttpServletRequest request) {
		return "true".equals(request.getHeader(HtmxHeaders.REQUEST));
	}

	public static boolean isBoosted(HttpServletRequest request) {
		return "true".equals(request.getHeader(HtmxHeaders.BOOSTED));
	}

	/**
	 * HTMX partial request: client sent HX-Request but it is not a full-page boosted
	 * navigation (i.e. a targeted swap such as search or pagination).
	 */
	public static boolean isPartial(HttpServletRequest request) {
		return isHtmxRequest(request) && !isBoosted(request);
	}

}
