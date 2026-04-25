package am.ik.blog.site.web;

import am.ik.blog.BlogProps;
import am.ik.blog.entry.Entry;
import am.ik.blog.entry.EntryClient;
import am.ik.blog.entry.EntryQuery;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the site's Atom 1.0 feed at {@code /rss}. The upstream Blog API has no feed
 * endpoint, so we aggregate the latest entries via {@link EntryClient} and render the XML
 * ourselves.
 */
@Controller
public class FeedController {

	static final int FEED_SIZE = 30;

	private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

	private static final MediaType ATOM_XML = MediaType.parseMediaType("application/atom+xml;charset=UTF-8");

	private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newInstance();

	private final EntryClient entryClient;

	private final BlogProps blogProps;

	public FeedController(EntryClient entryClient, BlogProps blogProps) {
		this.entryClient = entryClient;
		this.blogProps = blogProps;
	}

	@GetMapping(path = "/rss", produces = "application/atom+xml")
	public ResponseEntity<byte[]> feed() {
		List<Entry> entries = this.entryClient.findEntries(EntryQuery.builder().size(FEED_SIZE).build()).content();
		byte[] body = renderAtom(entries);
		return ResponseEntity.ok()
			.contentType(ATOM_XML)
			.cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
			.body(body);
	}

	private byte[] renderAtom(List<Entry> entries) {
		String baseUrl = stripTrailingSlash(this.blogProps.baseUrl());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			XMLStreamWriter writer = XML_OUTPUT_FACTORY.createXMLStreamWriter(out, "UTF-8");
			writer.writeStartDocument("UTF-8", "1.0");
			writer.writeStartElement("feed");
			writer.writeDefaultNamespace(ATOM_NS);

			writeText(writer, "id", baseUrl + "/");
			writeText(writer, "title", this.blogProps.name());
			if (StringUtils.hasText(this.blogProps.description())) {
				writeText(writer, "subtitle", this.blogProps.description());
			}
			writeText(writer, "updated", isoDate(feedUpdated(entries)));
			writeLink(writer, "self", baseUrl + "/rss");
			writeLink(writer, null, baseUrl + "/");

			for (Entry entry : entries) {
				writeEntry(writer, entry, baseUrl);
			}

			writer.writeEndElement();
			writer.writeEndDocument();
			writer.flush();
			writer.close();
		}
		catch (XMLStreamException e) {
			throw new IllegalStateException("Failed to render Atom feed", e);
		}
		return out.toByteArray();
	}

	private static void writeEntry(XMLStreamWriter writer, Entry entry, String baseUrl) throws XMLStreamException {
		writer.writeStartElement("entry");
		String entryUrl = baseUrl + "/entries/" + entry.entryId();
		writeText(writer, "id", entryUrl);
		writeText(writer, "title", entry.title());
		Instant updated = entry.updated().date();
		if (updated != null) {
			writeText(writer, "updated", isoDate(updated));
		}
		Instant created = entry.created().date();
		if (created != null) {
			writeText(writer, "published", isoDate(created));
		}
		writeLink(writer, null, entryUrl);
		String summary = entry.frontMatter().summary();
		if (StringUtils.hasText(summary)) {
			writer.writeStartElement("summary");
			writer.writeAttribute("type", "html");
			writer.writeCharacters(summary);
			writer.writeEndElement();
		}
		writer.writeStartElement("author");
		writeText(writer, "name", entry.created().name());
		writer.writeEndElement();
		writer.writeEndElement();
	}

	private static void writeText(XMLStreamWriter writer, String element, String value) throws XMLStreamException {
		writer.writeStartElement(element);
		writer.writeCharacters(value);
		writer.writeEndElement();
	}

	private static void writeLink(XMLStreamWriter writer, @Nullable String rel, String href) throws XMLStreamException {
		writer.writeEmptyElement("link");
		if (rel != null) {
			writer.writeAttribute("rel", rel);
		}
		writer.writeAttribute("href", href);
	}

	/** Atom requires a feed-level {@code <updated>}. Use the newest entry, or now. */
	private static Instant feedUpdated(List<Entry> entries) {
		Instant latest = null;
		for (Entry entry : entries) {
			Instant d = entry.updated().date();
			if (d != null && (latest == null || d.isAfter(latest))) {
				latest = d;
			}
		}
		return latest == null ? Instant.now() : latest;
	}

	private static String isoDate(Instant instant) {
		return DateTimeFormatter.ISO_INSTANT.format(instant);
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
