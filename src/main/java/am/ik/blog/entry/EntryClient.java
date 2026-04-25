package am.ik.blog.entry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Thin REST client over the upstream Blog API.
 *
 * <p>
 * Each operation has a no-tenant form (routes to {@code /entries…}) and a tenant-aware
 * overload that prefixes the path with {@code /tenants/{tenantId}}. The English site is
 * served from a separate upstream tenant ({@code tenantId=en} →
 * {@code /tenants/en/entries…}).
 */
@Component
public class EntryClient {

	private static final ParameterizedTypeReference<CursorPage<Entry>> ENTRY_PAGE = new ParameterizedTypeReference<>() {
	};

	private static final ParameterizedTypeReference<List<TagAndCount>> TAG_LIST = new ParameterizedTypeReference<>() {
	};

	private static final ParameterizedTypeReference<List<List<Category>>> CATEGORY_TREE = new ParameterizedTypeReference<>() {
	};

	private final RestClient restClient;

	public EntryClient(RestClient.Builder builder, EntryApiProps props) {
		RestClient.Builder b = builder.baseUrl(props.baseUrl())
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		// Attach Basic credentials when configured. The upstream API requires auth
		// even for public reads; the default `blog-ui` principal has no privileged
		// scopes, so sending it unconditionally is safe.
		if (StringUtils.hasText(props.username())) {
			b = b.defaultHeaders(headers -> headers.setBasicAuth(props.username(), props.password()));
		}
		this.restClient = b.build();
	}

	public CursorPage<Entry> findEntries(EntryQuery query) {
		return findEntries(query, null);
	}

	public CursorPage<Entry> findEntries(EntryQuery query, @Nullable String tenantId) {
		String base = entriesBase(tenantId);
		CursorPage<Entry> page = this.restClient.get().uri(uriBuilder -> {
			uriBuilder.path(base);
			if (StringUtils.hasText(query.query())) {
				uriBuilder.queryParam("query", query.query());
			}
			if (StringUtils.hasText(query.tag())) {
				uriBuilder.queryParam("tag", query.tag());
			}
			if (query.categories() != null && !query.categories().isEmpty()) {
				uriBuilder.queryParam("categories", String.join(",", query.categories()));
			}
			if (StringUtils.hasText(query.cursor())) {
				uriBuilder.queryParam("cursor", query.cursor());
				uriBuilder.queryParam("direction", query.direction().name());
			}
			uriBuilder.queryParam("size", query.size());
			return uriBuilder.build();
		}).retrieve().body(ENTRY_PAGE);
		return page == null ? new CursorPage<>(List.of(), query.size(), false, false, null, null) : page;
	}

	public Optional<Entry> findById(Long entryId) {
		return findById(entryId, null);
	}

	public Optional<Entry> findById(Long entryId, @Nullable String tenantId) {
		// Handle 404 at the RestClient layer so the HTTP client span stays successful —
		// throwing and catching RestClientResponseException would still mark the span as
		// error in tracing instrumentation even if we translate it to Optional.empty().
		ResponseEntity<Entry> response = this.restClient.get()
			.uri(entriesBase(tenantId) + "/{id}", entryId)
			.retrieve()
			.onStatus(status -> status == HttpStatus.NOT_FOUND, (request, res) -> {
				// Swallow: translated into Optional.empty() below.
			})
			.toEntity(Entry.class);
		if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
			return Optional.empty();
		}
		return Optional.ofNullable(response.getBody());
	}

	public Optional<String> findMarkdownById(Long entryId) {
		return findMarkdownById(entryId, null);
	}

	public Optional<String> findMarkdownById(Long entryId, @Nullable String tenantId) {
		// See findById for why 404 is handled via onStatus rather than try/catch.
		ResponseEntity<String> response = this.restClient.get()
			.uri(entriesBase(tenantId) + "/{id}.md", entryId)
			.retrieve()
			.onStatus(status -> status == HttpStatus.NOT_FOUND, (request, res) -> {
				// Swallow: translated into Optional.empty() below.
			})
			.toEntity(String.class);
		if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
			return Optional.empty();
		}
		return Optional.ofNullable(response.getBody());
	}

	/**
	 * Issues a conditional {@code HEAD} against the upstream entry endpoint so callers
	 * can short-circuit with {@code 304 Not Modified} without transferring the body. The
	 * upstream honors {@code If-Modified-Since} via Spring's
	 * {@code WebRequest.checkNotModified} and returns 304 when the entry's
	 * {@code updated.date} is not newer than the supplied instant.
	 *
	 * <p>
	 * 304 and 404 statuses are not turned into exceptions — callers inspect
	 * {@link ResponseEntity#getStatusCode()} to decide.
	 */
	public ResponseEntity<Void> headEntry(Long entryId, Instant ifModifiedSince, @Nullable String tenantId) {
		return this.restClient.head()
			.uri(entriesBase(tenantId) + "/{id}", entryId)
			.headers(headers -> headers.setIfModifiedSince(ifModifiedSince))
			.retrieve()
			.onStatus(status -> status == HttpStatus.NOT_MODIFIED || status == HttpStatus.NOT_FOUND,
					(request, response) -> {
						// Swallow: let caller branch on the status code.
					})
			.toBodilessEntity();
	}

	public List<TagAndCount> findAllTags() {
		return findAllTags(null);
	}

	public List<TagAndCount> findAllTags(@Nullable String tenantId) {
		List<TagAndCount> tags = this.restClient.get().uri(tenantPrefix(tenantId) + "/tags").retrieve().body(TAG_LIST);
		return tags == null ? List.of() : tags;
	}

	public List<List<Category>> findAllCategories() {
		return findAllCategories(null);
	}

	public List<List<Category>> findAllCategories(@Nullable String tenantId) {
		List<List<Category>> categories = this.restClient.get()
			.uri(tenantPrefix(tenantId) + "/categories")
			.retrieve()
			.body(CATEGORY_TREE);
		return categories == null ? List.of() : categories;
	}

	private static String entriesBase(@Nullable String tenantId) {
		return tenantPrefix(tenantId) + "/entries";
	}

	private static String tenantPrefix(@Nullable String tenantId) {
		return StringUtils.hasText(tenantId) ? "/tenants/" + tenantId : "";
	}

}
