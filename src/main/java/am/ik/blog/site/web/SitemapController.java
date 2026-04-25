package am.ik.blog.site.web;

import am.ik.blog.BlogProps;
import am.ik.blog.entry.Category;
import am.ik.blog.entry.CursorPage;
import am.ik.blog.entry.Entry;
import am.ik.blog.entry.EntryClient;
import am.ik.blog.entry.EntryQuery;
import am.ik.blog.entry.TagAndCount;
import am.ik.blog.entry.web.CategoryUrl;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves {@code /sitemap.xml} as a URL discovery hint for search engines. Aggregates
 * static pages, every entry, every tag, and every category chain into a single sitemap
 * 0.9 document.
 */
@Controller
public class SitemapController {

	static final int ENTRY_PAGE_SIZE = 100;

	/** Sitemap 0.9 limit per file. */
	static final int MAX_URLS = 50_000;

	/** Safety net so a buggy cursor cannot spin forever. */
	static final int MAX_ENTRY_PAGES = MAX_URLS / ENTRY_PAGE_SIZE + 10;

	/** Upstream tenant for the English site (mirrors {@code EntryController}). */
	private static final String EN_TENANT_ID = "en";

	private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

	private static final MediaType SITEMAP_XML = MediaType.parseMediaType("application/xml;charset=UTF-8");

	private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newInstance();

	private final EntryClient entryClient;

	private final BlogProps blogProps;

	public SitemapController(EntryClient entryClient, BlogProps blogProps) {
		this.entryClient = entryClient;
		this.blogProps = blogProps;
	}

	@GetMapping(path = "/sitemap.xml", produces = "application/xml")
	public ResponseEntity<byte[]> sitemap() {
		String baseUrl = stripTrailingSlash(this.blogProps.baseUrl());
		List<Url> urls = collectUrls(baseUrl);
		byte[] body = renderSitemap(urls);
		return ResponseEntity.ok()
			.contentType(SITEMAP_XML)
			.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
			.body(body);
	}

	private List<Url> collectUrls(String baseUrl) {
		List<Url> urls = new ArrayList<>();
		// Static pages. `/entries/en` is the English tenant's list page (React parity).
		urls.add(new Url(baseUrl + "/", null));
		urls.add(new Url(baseUrl + "/entries", null));
		urls.add(new Url(baseUrl + "/entries/en", null));
		urls.add(new Url(baseUrl + "/tags", null));
		urls.add(new Url(baseUrl + "/categories", null));
		urls.add(new Url(baseUrl + "/aboutme", null));

		// Entry detail pages (Japanese, default tenant).
		for (Entry entry : fetchAllEntries(null)) {
			if (urls.size() >= MAX_URLS) {
				break;
			}
			urls.add(new Url(baseUrl + "/entries/" + entry.entryId(), entry.updated().date()));
		}

		// English entry detail pages — each entry carries its own `<lastmod>`.
		for (Entry entry : fetchAllEntries(EN_TENANT_ID)) {
			if (urls.size() >= MAX_URLS) {
				break;
			}
			urls.add(new Url(baseUrl + "/entries/" + entry.entryId() + "/en", entry.updated().date()));
		}

		// Tag landing pages (task 018 URL shape).
		for (TagAndCount tag : this.entryClient.findAllTags()) {
			if (urls.size() >= MAX_URLS) {
				break;
			}
			String loc = UriComponentsBuilder.fromUriString(baseUrl)
				.path("/tags")
				.pathSegment(tag.name())
				.path("/entries")
				.encode(StandardCharsets.UTF_8)
				.build()
				.toUriString();
			urls.add(new Url(loc, null));
		}

		// Category chain landing pages (task 019 URL shape).
		for (List<Category> chain : this.entryClient.findAllCategories()) {
			if (urls.size() >= MAX_URLS) {
				break;
			}
			if (chain.isEmpty()) {
				continue;
			}
			List<String> names = chain.stream().map(Category::name).toList();
			String loc = UriComponentsBuilder.fromUriString(baseUrl)
				.path("/categories")
				.pathSegment(CategoryUrl.of(names))
				.path("/entries")
				.encode(StandardCharsets.UTF_8)
				.build()
				.toUriString();
			urls.add(new Url(loc, null));
		}
		return urls;
	}

	/** Walks the cursor-paginated entries API until exhausted or the URL cap is hit. */
	private List<Entry> fetchAllEntries(@Nullable String tenantId) {
		List<Entry> all = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		String cursor = null;
		for (int i = 0; i < MAX_ENTRY_PAGES && all.size() < MAX_URLS; i++) {
			EntryQuery query = EntryQuery.builder().size(ENTRY_PAGE_SIZE).cursor(cursor).build();
			CursorPage<Entry> page = this.entryClient.findEntries(query, tenantId);
			for (Entry entry : page.content()) {
				if (seen.add(entry.entryId())) {
					all.add(entry);
				}
			}
			String next = effectiveNextCursor(page);
			// Stop if no next cursor OR the cursor did not advance (duplicate page would
			// otherwise loop forever when the API can't supply one).
			if (next == null || next.equals(cursor)) {
				break;
			}
			cursor = next;
		}
		return all;
	}

	/**
	 * Same fallback as {@code EntryController#effectiveNextCursor}: the upstream
	 * {@code findLatest} path sets {@code hasNext} without emitting {@code nextCursor},
	 * so synthesise one from the oldest entry's update timestamp.
	 */
	@Nullable private static String effectiveNextCursor(CursorPage<Entry> page) {
		if (page.nextCursor() != null) {
			return page.nextCursor();
		}
		if (!page.hasNext() || page.content().isEmpty()) {
			return null;
		}
		Entry last = page.content().get(page.content().size() - 1);
		Instant date = last.updated().date();
		return date == null ? null : date.toString();
	}

	private static byte[] renderSitemap(List<Url> urls) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			XMLStreamWriter writer = XML_OUTPUT_FACTORY.createXMLStreamWriter(out, "UTF-8");
			writer.writeStartDocument("UTF-8", "1.0");
			writer.writeStartElement("urlset");
			writer.writeDefaultNamespace(SITEMAP_NS);
			for (Url url : urls) {
				writer.writeStartElement("url");
				writer.writeStartElement("loc");
				writer.writeCharacters(url.loc());
				writer.writeEndElement();
				if (url.lastmod() != null) {
					writer.writeStartElement("lastmod");
					writer.writeCharacters(DateTimeFormatter.ISO_INSTANT.format(url.lastmod()));
					writer.writeEndElement();
				}
				writer.writeEndElement();
			}
			writer.writeEndElement();
			writer.writeEndDocument();
			writer.flush();
			writer.close();
		}
		catch (XMLStreamException e) {
			throw new IllegalStateException("Failed to render sitemap", e);
		}
		return out.toByteArray();
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private record Url(String loc, @Nullable Instant lastmod) {
	}

}
