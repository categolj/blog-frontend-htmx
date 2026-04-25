package am.ik.blog.entry.web;

/**
 * Namespace holder for the switch-language link model rendered next to the MD badge on
 * entry-detail pages. Reader-facing label and site-relative URL are pre-computed by the
 * controller — the template just interpolates them.
 */
public final class LanguageToggle {

	private LanguageToggle() {
	}

	/**
	 * Switch-language hyperlink: {@code label} is the target language shown to the reader
	 * ("EN" or "JA"), {@code url} is the site-relative href.
	 */
	public record Link(String label, String url) {
	}

}
