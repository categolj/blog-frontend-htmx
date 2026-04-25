package am.ik.blog.markdown;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.jspecify.annotations.Nullable;

/**
 * Renders GitHub-style alerts that use a blockquote starting with {@code [!TYPE]} as a
 * marker. Supported types are NOTE, TIP, IMPORTANT, WARNING, CAUTION; other blockquotes
 * fall through to the default rendering.
 */
public final class GitHubAlertExtension implements HtmlRenderer.HtmlRendererExtension {

	public static Extension create() {
		return new GitHubAlertExtension();
	}

	@Override
	public void extend(HtmlRenderer.Builder rendererBuilder) {
		rendererBuilder.nodeRendererFactory(AlertBlockQuoteRenderer::new);
	}

	static final class AlertBlockQuoteRenderer implements NodeRenderer {

		private static final Pattern MARKER = Pattern
			.compile("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\][\\t ]*\\r?\\n?");

		private final HtmlNodeRendererContext context;

		private final HtmlWriter html;

		AlertBlockQuoteRenderer(HtmlNodeRendererContext context) {
			this.context = context;
			this.html = context.getWriter();
		}

		@Override
		public Set<Class<? extends Node>> getNodeTypes() {
			return Set.of(BlockQuote.class);
		}

		@Override
		public void render(Node node) {
			BlockQuote blockQuote = (BlockQuote) node;
			String type = detect(blockQuote);
			if (type == null) {
				renderBlockQuote(blockQuote);
				return;
			}
			strip(blockQuote);
			this.html.line();
			this.html.tag("div", Map.of("class", "markdown-alert markdown-alert-" + type.toLowerCase()));
			this.html.line();
			this.html.tag("p", Map.of("class", "markdown-alert-title"));
			this.html.text(title(type));
			this.html.tag("/p");
			this.html.line();
			renderChildren(blockQuote);
			this.html.line();
			this.html.tag("/div");
			this.html.line();
		}

		private void renderBlockQuote(BlockQuote blockQuote) {
			this.html.line();
			this.html.tag("blockquote", this.context.extendAttributes(blockQuote, "blockquote", Map.of()));
			this.html.line();
			renderChildren(blockQuote);
			this.html.line();
			this.html.tag("/blockquote");
			this.html.line();
		}

		private void renderChildren(Node parent) {
			Node child = parent.getFirstChild();
			while (child != null) {
				Node next = child.getNext();
				this.context.render(child);
				child = next;
			}
		}

		@Nullable private static String detect(BlockQuote blockQuote) {
			if (!(blockQuote.getFirstChild() instanceof Paragraph p)) {
				return null;
			}
			if (!(p.getFirstChild() instanceof Text t)) {
				return null;
			}
			Matcher m = MARKER.matcher(t.getLiteral());
			return m.lookingAt() ? m.group(1) : null;
		}

		private static void strip(BlockQuote blockQuote) {
			Paragraph p = (Paragraph) blockQuote.getFirstChild();
			Text t = (Text) p.getFirstChild();
			Matcher m = MARKER.matcher(t.getLiteral());
			if (!m.lookingAt()) {
				return;
			}
			String remaining = t.getLiteral().substring(m.end());
			if (remaining.isEmpty()) {
				Node next = t.getNext();
				t.unlink();
				if (next instanceof SoftLineBreak || next instanceof HardLineBreak) {
					next.unlink();
				}
				if (p.getFirstChild() == null) {
					p.unlink();
				}
			}
			else {
				t.setLiteral(remaining);
			}
		}

		private static String title(String type) {
			return switch (type) {
				case "NOTE" -> "Note";
				case "TIP" -> "Tip";
				case "IMPORTANT" -> "Important";
				case "WARNING" -> "Warning";
				case "CAUTION" -> "Caution";
				default -> type;
			};
		}

	}

}
