package am.ik.blog.markdown;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTests {

	private final MarkdownRenderer renderer = new MarkdownRenderer();

	@Test
	void rendersBasicMarkdown() {
		String html = this.renderer.render("""
				## Hello

				A **bold** statement and [a link](https://example.com).
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<h2 id="hello">Hello</h2>
				<p>A <strong>bold</strong> statement and <a href="https://example.com">a link</a>.</p>
				""");
	}

	@Test
	void rendersEmptyForBlankInput() {
		assertThat(this.renderer.render("")).isEqualTo("");
		assertThat(this.renderer.render("   \n\n ")).isEqualTo("");
	}

	@Test
	void rendersFencedCodeBlockWithLanguageClass() {
		String html = this.renderer.render("""
				```java
				int x = 1;
				```
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<pre><code class="language-java">int x = 1;
				</code></pre>
				""");
	}

	@Test
	void rendersGfmTable() {
		String html = this.renderer.render("""
				| A | B |
				|---|---|
				| 1 | 2 |
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<table>
				<thead>
				<tr>
				<th>A</th>
				<th>B</th>
				</tr>
				</thead>
				<tbody>
				<tr>
				<td>1</td>
				<td>2</td>
				</tr>
				</tbody>
				</table>
				""");
	}

	@Test
	void rendersGfmStrikethrough() {
		String html = this.renderer.render("This is ~~deleted~~ text.");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<p>This is <del>deleted</del> text.</p>
				""");
	}

	@Test
	void rendersGfmTaskList() {
		String html = this.renderer.render("""
				- [x] done
				- [ ] pending
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<ul>
				<li><input type="checkbox" disabled="" checked=""> done</li>
				<li><input type="checkbox" disabled=""> pending</li>
				</ul>
				""");
	}

	@Test
	void rendersGitHubNoteAlert() {
		String html = this.renderer.render("""
				> [!NOTE]
				> Useful info.
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<div class="markdown-alert markdown-alert-note">
				<p class="markdown-alert-title">Note</p>
				<p>Useful info.</p>
				</div>
				""");
	}

	@Test
	void rendersGitHubWarningAlert() {
		String html = this.renderer.render("""
				> [!WARNING]
				> Be careful.
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<div class="markdown-alert markdown-alert-warning">
				<p class="markdown-alert-title">Warning</p>
				<p>Be careful.</p>
				</div>
				""");
	}

	@Test
	void rendersOrdinaryBlockquoteWhenNoAlertMarker() {
		String html = this.renderer.render("""
				> Plain quote
				> second line.
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<blockquote>
				<p>Plain quote
				second line.</p>
				</blockquote>
				""");
	}

	@Test
	void replacesTocMarkerWithNestedList() {
		String html = this.renderer.render("""
				<!-- toc -->

				## First

				### Sub

				## Second
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<nav class="toc" aria-label="Table of contents">
				<ul>
				<li><a href="#first">First</a></li>
				<ul>
				<li><a href="#sub">Sub</a></li>
				</ul>
				<li><a href="#second">Second</a></li>
				</ul>
				</nav>
				<h2 id="first">First</h2>
				<h3 id="sub">Sub</h3>
				<h2 id="second">Second</h2>
				""");
	}

	@Test
	void emptyTocWhenNoHeadings() {
		String html = this.renderer.render("""
				<!-- toc -->

				Just some text.
				""");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<nav class="toc" aria-label="Table of contents">
				</nav>
				<p>Just some text.</p>
				""");
	}

	@Test
	void autolinksBareUrls() {
		String html = this.renderer.render("visit https://example.com");

		assertThat(html).isEqualToNormalizingWhitespace("""
				<p>visit <a href="https://example.com">https://example.com</a></p>
				""");
	}

}
