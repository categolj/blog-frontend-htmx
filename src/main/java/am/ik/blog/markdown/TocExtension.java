package am.ik.blog.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.heading.anchor.IdGenerator;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.parser.PostProcessor;

/**
 * Replaces <code>&lt;!-- toc --&gt;</code> markers in the markdown source with a nested
 * list of links to every heading in the document. Heading ids are produced with the same
 * generator used by {@code HeadingAnchorExtension} so anchors stay in sync.
 */
public final class TocExtension implements Parser.ParserExtension {

	private static final Pattern TOC_MARKER = Pattern.compile("\\s*<!--\\s*toc\\s*-->\\s*",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	public static Extension create() {
		return new TocExtension();
	}

	@Override
	public void extend(Parser.Builder parserBuilder) {
		parserBuilder.postProcessor(new TocPostProcessor());
	}

	static final class TocPostProcessor implements PostProcessor {

		@Override
		public Node process(Node node) {
			Collector collector = new Collector();
			node.accept(collector);
			if (collector.tocMarkers.isEmpty()) {
				return node;
			}
			String toc = renderToc(collector.headings);
			for (HtmlBlock marker : collector.tocMarkers) {
				HtmlBlock replacement = new HtmlBlock();
				replacement.setLiteral(toc);
				marker.insertAfter(replacement);
				marker.unlink();
			}
			return node;
		}

	}

	static String renderToc(List<HeadingInfo> headings) {
		StringBuilder sb = new StringBuilder();
		sb.append("<nav class=\"toc\" aria-label=\"Table of contents\">\n");
		if (headings.isEmpty()) {
			sb.append("</nav>\n");
			return sb.toString();
		}
		int baseLevel = headings.stream().mapToInt(HeadingInfo::level).min().orElse(1);
		int currentLevel = baseLevel - 1;
		for (HeadingInfo h : headings) {
			int level = h.level();
			while (currentLevel < level) {
				sb.append("<ul>\n");
				currentLevel++;
			}
			while (currentLevel > level) {
				sb.append("</ul>\n");
				currentLevel--;
			}
			sb.append("<li><a href=\"#")
				.append(escapeAttr(h.id()))
				.append("\">")
				.append(escape(h.text()))
				.append("</a></li>\n");
		}
		while (currentLevel >= baseLevel) {
			sb.append("</ul>\n");
			currentLevel--;
		}
		sb.append("</nav>\n");
		return sb.toString();
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String escapeAttr(String s) {
		return escape(s).replace("\"", "&quot;");
	}

	record HeadingInfo(int level, String text, String id) {
	}

	static final class Collector extends AbstractVisitor {

		final List<HeadingInfo> headings = new ArrayList<>();

		final List<HtmlBlock> tocMarkers = new ArrayList<>();

		private final IdGenerator idGenerator = IdGenerator.builder().build();

		@Override
		public void visit(Heading heading) {
			String text = textOf(heading);
			String id = this.idGenerator.generateId(text);
			this.headings.add(new HeadingInfo(heading.getLevel(), text, id));
			super.visit(heading);
		}

		@Override
		public void visit(HtmlBlock htmlBlock) {
			if (TOC_MARKER.matcher(htmlBlock.getLiteral()).matches()) {
				this.tocMarkers.add(htmlBlock);
			}
		}

		private static String textOf(Node node) {
			StringBuilder sb = new StringBuilder();
			node.accept(new AbstractVisitor() {
				@Override
				public void visit(Text text) {
					sb.append(text.getLiteral());
				}
			});
			return sb.toString();
		}

	}

}
