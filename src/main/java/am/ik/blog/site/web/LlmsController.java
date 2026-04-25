package am.ik.blog.site.web;

import am.ik.blog.BlogProps;
import am.ik.blog.entry.CursorPage;
import am.ik.blog.entry.Entry;
import am.ik.blog.entry.EntryClient;
import am.ik.blog.entry.EntryQuery;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves plain-text, Markdown-flavoured discovery endpoints for LLMs and automated
 * crawlers following the {@code llms.txt} convention
 * (<a href="https://llmstxt.org/">llmstxt.org</a>).
 *
 * <p>
 * The raw per-entry Markdown paths ({@code /entries/{id}.md},
 * {@code /entries/{id}/en.md}) are served by {@code EntryController} — this controller
 * only covers the overview and list endpoints referenced from {@code /llms.txt}.
 */
@RestController
public class LlmsController {

	/** Upstream tenant identifier for the English-language site. */
	private static final String EN_TENANT_ID = "en";

	private final EntryClient entryClient;

	private final BlogProps blogProps;

	public LlmsController(EntryClient entryClient, BlogProps blogProps) {
		this.entryClient = entryClient;
		this.blogProps = blogProps;
	}

	@GetMapping(path = "/llms.txt", produces = MediaType.TEXT_PLAIN_VALUE)
	public String llms() {
		CursorPage<Entry> pageJa = this.entryClient.findEntries(EntryQuery.builder().build());
		CursorPage<Entry> pageEn = this.entryClient.findEntries(EntryQuery.builder().build(), EN_TENANT_ID);
		String entriesJa = pageJa.content()
			.stream()
			.map(entry -> "- [%s](/entries/%d.md) - 最終更新時刻 %s".formatted(entry.title(), entry.entryId(),
					entry.updated().date()))
			.collect(Collectors.joining("\n"));
		String entriesEn = pageEn.content()
			.stream()
			.map(entry -> "- [%s](/entries/%d/en.md) - Last Updated at %s".formatted(entry.title(), entry.entryId(),
					entry.updated().date()))
			.collect(Collectors.joining("\n"));
		return """
				# %s
				%s

				## ナビゲーション
				- [記事一覧](/entries.md): Markdown形式の記事一覧
				- [記事一覧 (English)](/entries/en.md): Markdown形式の記事一覧
				- [記事ページ](/entries/[entryId].md): `entryId`に対応するMarkdown形式の記事ページ
				- [記事ページ (English)](/entries/[entryId]/en.md): `entryId`に対応する英語に翻訳されたMarkdown形式の記事ページ。ただし、未翻訳の場合は404エラーになります。

				## 最新の記事 (日本語版)
				%s

				## Latest Entries (English)
				%s
				"""
			.formatted(this.blogProps.name(), this.blogProps.description(), entriesJa, entriesEn);
	}

	/**
	 * Markdown-flavoured list of entries for LLM consumption.
	 *
	 * <p>
	 * Two URL shapes share this handler:
	 * <ul>
	 * <li>{@code /entries.md} — default (Japanese) tenant.
	 * <li>{@code /entries/{tenantId}.md} — alternate tenants, e.g.
	 * {@code /entries/en.md}. The regex {@code [a-z]+} disambiguates against the
	 * per-entry {@code /entries/{id}.md} route in {@code EntryController}.
	 * </ul>
	 */
	@GetMapping(path = { "/entries.md", "/entries/{tenantId:[a-z]+}.md" }, produces = MediaType.TEXT_PLAIN_VALUE)
	public String entries(@RequestParam(name = "cursor", required = false) @Nullable String cursor,
			@Nullable @PathVariable(required = false) String tenantId) {
		EntryQuery query = EntryQuery.builder().cursor(cursor).build();
		List<Entry> content = this.entryClient.findEntries(query, tenantId).content();
		String entryPath = tenantId == null ? "" : "/" + tenantId;
		String entries = content.stream()
			.map(entry -> "- [%s](/entries/%d%s.md) - Last Updated at %s".formatted(entry.title(), entry.entryId(),
					entryPath, entry.updated().date()))
			.collect(Collectors.joining("\n"));
		String more = content.isEmpty() ? ""
				: "/entries" + entryPath + ".md?cursor=" + content.get(content.size() - 1).updated().date();
		return """
				# %s
				## Entries

				%s

				[More Entries](%s)
				""".formatted(this.blogProps.name(), entries, more);
	}

}
