package am.ik.blog.asset;

import am.ik.blog.asset.AssetsVersion.ParsedEtag;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetsVersionTests {

	private static ResourceUrlProvider stubProvider(Map<String, String> mappings) {
		ResourceUrlProvider provider = mock(ResourceUrlProvider.class);
		mappings.forEach((path, resolved) -> when(provider.getForLookupPath(path)).thenReturn(resolved));
		return provider;
	}

	@Test
	void digestIsPendingSentinelBeforeCompute() {
		AssetsVersion assets = new AssetsVersion(stubProvider(Map.of()));

		// Sentinel chosen so ETags pre-ApplicationReadyEvent still parse cleanly.
		assertThat(assets.digest()).isEqualTo("0");
	}

	@Test
	void computeDigestHashesResolvedAssetUrls() {
		AssetsVersion assets = new AssetsVersion(
				stubProvider(Map.of("/js/app.min.js", "/js/app-aaa.min.js", "/css/style.css", "/css/style-bbb.css")));

		assets.computeDigest();

		// 16 lowercase hex chars — the SHA-256 truncation produced by the class.
		assertThat(assets.digest()).matches("[0-9a-f]{16}");
	}

	@Test
	void digestChangesWhenResolvedUrlsChange() {
		AssetsVersion before = new AssetsVersion(
				stubProvider(Map.of("/js/app.min.js", "/js/app-v1.min.js", "/css/style.css", "/css/style-v1.css")));
		before.computeDigest();
		AssetsVersion after = new AssetsVersion(
				stubProvider(Map.of("/js/app.min.js", "/js/app-v2.min.js", "/css/style.css", "/css/style-v1.css")));
		after.computeDigest();

		assertThat(after.digest()).isNotEqualTo(before.digest());
	}

	@Test
	void digestIsStableForSameResolvedUrls() {
		Map<String, String> resolved = Map.of("/js/app.min.js", "/js/app-fixed.min.js", "/css/style.css",
				"/css/style-fixed.css");

		AssetsVersion a = new AssetsVersion(stubProvider(resolved));
		a.computeDigest();
		AssetsVersion b = new AssetsVersion(stubProvider(resolved));
		b.computeDigest();

		assertThat(b.digest()).isEqualTo(a.digest());
	}

	@Test
	void computeDigestFallsBackToRawPathWhenResolverReturnsNull() {
		// Dev-profile case: ResourceUrlProvider has no mapping and returns null;
		// hashing the raw paths still yields a deterministic non-sentinel digest.
		AssetsVersion assets = new AssetsVersion(stubProvider(Map.of()));

		assets.computeDigest();

		assertThat(assets.digest()).matches("[0-9a-f]{16}").isNotEqualTo("0");
	}

	@Test
	void withFixedDigestSkipsComputationAndExposesGivenDigest() {
		AssetsVersion assets = AssetsVersion.withFixedDigest("fixed123");

		// Running the lifecycle hook must be a no-op on fixed-digest instances
		// otherwise tests that pin the digest would drift.
		assets.computeDigest();

		assertThat(assets.digest()).isEqualTo("fixed123");
	}

	@Test
	void formatEtagCombinesEntryEpochAndDigest() {
		AssetsVersion assets = AssetsVersion.withFixedDigest("abcd1234");
		Instant entryUpdated = Instant.parse("2026-04-10T09:30:00Z");

		String etag = assets.formatEtag(entryUpdated);

		assertThat(etag).isEqualTo("W/\"" + entryUpdated.getEpochSecond() + "-abcd1234\"");
	}

	@Test
	void formatEtagCollapsesNullToEpochZero() {
		AssetsVersion assets = AssetsVersion.withFixedDigest("abcd1234");

		assertThat(assets.formatEtag(null)).isEqualTo("W/\"0-abcd1234\"");
	}

	@Test
	void formatEtagCollapsesUnixEpochSentinelToZero() {
		// Upstream API emits 1970-01-01T00:00:00Z for entries never edited after
		// import — treat it as "no timestamp" so the ETag stays stable across those.
		AssetsVersion assets = AssetsVersion.withFixedDigest("abcd1234");

		assertThat(assets.formatEtag(Instant.EPOCH)).isEqualTo("W/\"0-abcd1234\"");
	}

	@Test
	void parseIfNoneMatchSplitsWeakEtag() {
		AssetsVersion assets = AssetsVersion.withFixedDigest("ignored");

		ParsedEtag parsed = assets.parseIfNoneMatch("W/\"1775813400-abcd1234\"");

		assertThat(parsed).isEqualTo(new ParsedEtag(1775813400L, "abcd1234"));
	}

	@Test
	void parseIfNoneMatchAcceptsStrongEtagWithoutWeakPrefix() {
		// The server only emits Weak ETags, but some intermediaries / clients may
		// strip the W/ prefix; accept both shapes rather than 400-ing on the client.
		AssetsVersion assets = AssetsVersion.withFixedDigest("ignored");

		ParsedEtag parsed = assets.parseIfNoneMatch("\"42-zzz\"");

		assertThat(parsed).isEqualTo(new ParsedEtag(42L, "zzz"));
	}

	@Test
	void parseIfNoneMatchPicksFirstTagFromCommaSeparatedList() {
		AssetsVersion assets = AssetsVersion.withFixedDigest("ignored");

		ParsedEtag parsed = assets.parseIfNoneMatch("W/\"100-aaa\", W/\"200-bbb\"");

		assertThat(parsed).isEqualTo(new ParsedEtag(100L, "aaa"));
	}

	@Test
	void parseIfNoneMatchPreservesDashesInsideAssetDigest() {
		// Split is on the first dash only so digests that happen to contain dashes
		// survive round-tripping.
		AssetsVersion assets = AssetsVersion.withFixedDigest("ignored");

		ParsedEtag parsed = assets.parseIfNoneMatch("W/\"50-part1-part2\"");

		assertThat(parsed).isEqualTo(new ParsedEtag(50L, "part1-part2"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "not-a-tag", "\"no-dash\"", "W/\"not-numeric-epoch\"", "W/\"123\"",
			"W/\"-abc\"", "W/\"123-\"", "W/missing-quotes" })
	void parseIfNoneMatchReturnsNullForMalformedHeaders(String header) {
		AssetsVersion assets = AssetsVersion.withFixedDigest("ignored");

		assertThat(assets.parseIfNoneMatch(header)).isNull();
	}

	@Test
	void parseIfNoneMatchReturnsNullForNullHeader() {
		AssetsVersion assets = AssetsVersion.withFixedDigest("ignored");

		assertThat(assets.parseIfNoneMatch(null)).isNull();
	}

}
