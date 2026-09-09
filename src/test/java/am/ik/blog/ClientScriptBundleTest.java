package am.ik.blog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring between the client scripts on disk, the bundle definition in
 * {@code pom.xml} and the {@code <script>} tags in the default layout.
 *
 * <p>
 * A script that is not listed in the Closure Compiler {@code <includes>} is silently
 * missing from {@code app.min.js} — the prod profile is the only one that loads the
 * bundle, so the omission never shows up in development and the feature simply stops
 * working once deployed. The same goes in reverse for a script that is bundled but has no
 * tag in the layout's development branch.
 */
class ClientScriptBundleTest {

	private static final Path SCRIPT_DIR = Path.of("src/main/resources/static/js");

	/**
	 * Scripts that are deliberately kept out of {@code app.min.js}, and why. A new entry
	 * here should be a considered decision, not a way to silence this test.
	 */
	private static final Map<String, String> NOT_BUNDLED = Map.of("vendor/htmx.min.js",
			"htmx 4 uses private class fields, which Closure Compiler cannot parse; loaded as its own <script>",
			"mov-to-gif.js", "only the Lab tool page needs it, so it loads from that page's template");

	@Test
	void everyClientScriptIsEitherBundledOrDocumentedAsAnException() throws Exception {
		// Fails when a new file under static/js is neither added to the bundle nor
		// listed above — the case CLAUDE.md warns about when adding a script.
		assertThat(String.join("\n", scriptsOnDisk())).isEqualToNormalizingWhitespace(String.join("\n",
				Stream.concat(bundledScripts().stream(), NOT_BUNDLED.keySet().stream()).sorted().toList()));
	}

	@Test
	void developmentLayoutLoadsExactlyWhatTheBundleContains() throws Exception {
		// The non-prod branch of the layout replaces app.min.js with individual tags.
		// Order is part of the contract: concatenation does not reorder, so a library
		// that must precede its consumers in the bundle has to precede it here too.
		assertThat(String.join("\n", developmentScriptTags()))
			.isEqualToNormalizingWhitespace(String.join("\n", bundledScripts()));
	}

	private static List<String> scriptsOnDisk() throws IOException {
		try (Stream<Path> files = Files.walk(SCRIPT_DIR)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".js"))
				.map(path -> SCRIPT_DIR.relativize(path).toString())
				.sorted()
				.toList();
		}
	}

	/** The Closure Compiler {@code <includes>} list, in concatenation order. */
	private static List<String> bundledScripts()
			throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		Document pom;
		try (var pomStream = Files.newInputStream(Path.of("pom.xml"))) {
			pom = factory.newDocumentBuilder().parse(pomStream);
		}
		// local-name() keeps the expression readable despite the POM namespace.
		NodeList includes = (NodeList) XPathFactory.newInstance()
			.newXPath()
			.evaluate("//*[local-name()='plugin'][*[local-name()='artifactId']='closure-compiler-maven-plugin']"
					+ "//*[local-name()='include']", pom, XPathConstants.NODESET);
		return IntStream.range(0, includes.getLength())
			.mapToObj(i -> includes.item(i).getTextContent().trim())
			.toList();
	}

	/** The {@code /js/...} sources of the layout's non-bundled branch, in tag order. */
	private static List<String> developmentScriptTags() throws IOException {
		String layout = Files.readString(Path.of("src/main/resources/templates/layouts/default.mustache"),
				StandardCharsets.UTF_8);
		// The bundled branch closes with the same tag, so the search for the end has to
		// start from the opening of the non-bundled one.
		int start = layout.indexOf("{{^bundledJs}}");
		String developmentBranch = layout.substring(start, layout.indexOf("{{/bundledJs}}", start));
		Matcher sources = Pattern.compile("\\{\\{#src}}/js/(.+?)\\{\\{/src}}").matcher(developmentBranch);
		return sources.results().map(result -> result.group(1)).toList();
	}

}
