package am.ik.blog.config;

import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customises the embedded Tomcat to accept percent-encoded solidus ({@code %2F}) in
 * request paths. Tomcat 11's default is {@code REJECT}, which breaks tag landing URLs for
 * tags whose name itself contains a slash (e.g. {@code HTTP/3}). The href is emitted as
 * {@code /tags/HTTP%2F3/entries}; {@code PASS_THROUGH} leaves the {@code %2F} literal so
 * Spring's PathPatternParser sees {@code HTTP%2F3} as a single path segment, and the
 * controller's explicit {@link java.net.URLDecoder#decode} call recovers {@code HTTP/3}
 * as the tag value.
 */
@Configuration(proxyBeanMethods = false)
public class TomcatConfig {

	@Bean
	public WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSolidusPassThroughCustomizer() {
		return factory -> factory.addConnectorCustomizers(
				connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue()));
	}

}
