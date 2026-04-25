package am.ik.blog.markdown;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Server-side Markdown to HTML conversion. Rendering server-side is what allows web
 * crawlers to see the full article text. Supports GitHub Flavored Markdown: tables,
 * strikethrough, task list items, autolinks, and heading anchor ids.
 */
@Component
public class MarkdownRenderer {

	private final Parser parser;

	private final HtmlRenderer renderer;

	public MarkdownRenderer() {
		List<Extension> extensions = List.of(TablesExtension.create(), StrikethroughExtension.create(),
				TaskListItemsExtension.create(), AutolinkExtension.create(), HeadingAnchorExtension.create(),
				GitHubAlertExtension.create(), TocExtension.create());
		this.parser = Parser.builder().extensions(extensions).build();
		this.renderer = HtmlRenderer.builder().extensions(extensions).build();
	}

	public String render(@Nullable String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return "";
		}
		return this.renderer.render(this.parser.parse(markdown));
	}

}
