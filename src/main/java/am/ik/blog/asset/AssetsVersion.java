package am.ik.blog.asset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * Tracks a version identifier for the site's static assets (JS / CSS bundles) so entry
 * pages can mix it into their HTTP ETag. When the asset bundles are rebuilt with new
 * content, {@link ResourceUrlProvider} resolves {@code /js/app.min.js} etc. to URLs with
 * new content-hash segments; hashing those resolved URLs therefore changes if and only if
 * the asset content changes.
 *
 * <p>
 * The digest is computed once, on {@link ApplicationReadyEvent}, after the resource chain
 * has had a chance to warm its hash cache. Tests may swap this bean with a
 * {@code @Primary} instance returned by {@link #withFixedDigest(String)} to pin the
 * digest to a deterministic value.
 */
@Component
public class AssetsVersion {

	/**
	 * Asset paths included in the digest. Extend when new shared bundles referenced by
	 * the default layout are added. Only site-wide bundles belong here — per-page assets
	 * are not cache validators for the entry page's HTML envelope.
	 */
	private static final List<String> ASSET_PATHS = List.of("/js/app.min.js", "/css/style.css");

	private static final String PENDING_DIGEST = "0";

	private final @Nullable ResourceUrlProvider resourceUrlProvider;

	private volatile String digest;

	@Autowired
	public AssetsVersion(ResourceUrlProvider resourceUrlProvider) {
		this.resourceUrlProvider = resourceUrlProvider;
		this.digest = PENDING_DIGEST;
	}

	private AssetsVersion(@Nullable ResourceUrlProvider resourceUrlProvider, String digest) {
		this.resourceUrlProvider = resourceUrlProvider;
		this.digest = digest;
	}

	/**
	 * Returns an instance pinned to a fixed digest, bypassing the resource-chain lookup.
	 * Intended for tests so the ETag value is deterministic.
	 */
	public static AssetsVersion withFixedDigest(String digest) {
		return new AssetsVersion(null, digest);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void computeDigest() {
		if (this.resourceUrlProvider == null) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (String path : ASSET_PATHS) {
			String resolved = this.resourceUrlProvider.getForLookupPath(path);
			sb.append(StringUtils.hasLength(resolved) ? resolved : path).append('\n');
		}
		this.digest = sha256Hex16(sb.toString());
	}

	public String digest() {
		return this.digest;
	}

	/**
	 * Builds a weak ETag that combines the entry's update epoch seconds and the current
	 * asset digest. Null or epoch timestamps collapse to {@code 0}, so never-edited
	 * entries still get a cache validator that busts on asset change.
	 */
	public String formatEtag(@Nullable Instant entryUpdated) {
		long epoch = (entryUpdated == null || Instant.EPOCH.equals(entryUpdated)) ? 0L : entryUpdated.getEpochSecond();
		return "W/\"" + epoch + "-" + this.digest + "\"";
	}

	/**
	 * Extracts the first entity-tag from an {@code If-None-Match} header and splits it
	 * into {@code entryEpochSec} / {@code assetDigest}. Returns {@code null} for missing,
	 * malformed, or non-matching tag shapes so the caller falls through to the
	 * unconditional flow instead of erroring.
	 */
	@Nullable public ParsedEtag parseIfNoneMatch(@Nullable String header) {
		if (!StringUtils.hasText(header)) {
			return null;
		}
		// Browsers echo a single tag; take the first comma-separated entry to be safe.
		String first = header.split(",")[0].trim();
		if (first.startsWith("W/")) {
			first = first.substring(2);
		}
		if (first.length() < 2 || first.charAt(0) != '"' || first.charAt(first.length() - 1) != '"') {
			return null;
		}
		String value = first.substring(1, first.length() - 1);
		int dash = value.indexOf('-');
		if (dash <= 0 || dash == value.length() - 1) {
			return null;
		}
		try {
			long epoch = Long.parseLong(value, 0, dash, 10);
			String assetDigest = value.substring(dash + 1);
			return new ParsedEtag(epoch, assetDigest);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String sha256Hex16(String input) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	public record ParsedEtag(long entryEpochSec, String assetDigest) {
	}

}
