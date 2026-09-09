package am.ik.blog.config;

import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Locale;
import java.util.Objects;

import am.ik.blog.BlogProps;
import com.samskivert.mustache.Mustache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.springframework.web.util.UriUtils;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

	/**
	 * Request attribute carrying the language the page is being rendered in, written by
	 * the interceptor below once the model is known and read back by
	 * {@link #localeResolver()} during rendering.
	 */
	private static final String PAGE_LANGUAGE_ATTRIBUTE = WebConfig.class.getName() + ".pageLanguage";

	private final ObjectProvider<ResourceUrlProvider> resourceUrlProviders;

	private final BlogProps blogProps;

	private final boolean bundledJs;

	@Nullable private ResourceUrlProvider resourceUrlProvider = null;

	public WebConfig(ObjectProvider<ResourceUrlProvider> resourceUrlProviders, BlogProps blogProps,
			Environment environment) {
		this.resourceUrlProviders = resourceUrlProviders;
		this.blogProps = blogProps;
		this.bundledJs = environment.acceptsProfiles(Profiles.of("prod"));
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		if (this.resourceUrlProvider == null) {
			this.resourceUrlProvider = this.resourceUrlProviders.getObject();
		}
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
					@Nullable ModelAndView modelAndView) {
				if (modelAndView != null) {
					modelAndView.addObject("bundledJs", bundledJs);
					modelAndView.addObject("blog", blogProps);
					modelAndView.addObject("currentYear", Year.now().getValue());
					// Default canonical URL for the layout's metaSocial block.
					// putIfAbsent so a controller-supplied canonicalUrl (e.g. entry
					// detail) wins.
					modelAndView.getModelMap()
						.putIfAbsent("canonicalUrl", blogProps.baseUrl() + request.getRequestURI());
					// Default `<html lang>` value for the layout. Japanese is the primary
					// site language; controllers serving the English tenant override this
					// with "en".
					modelAndView.getModelMap().putIfAbsent("htmlLang", "ja");
					// Hand the resolved language to localeResolver() below, which runs
					// again when DispatcherServlet stamps the locale onto the response
					// just before rendering. Setting Content-Language here instead would
					// be pointless — that later setLocale() call overwrites it.
					if (modelAndView.getModelMap().get("htmlLang") instanceof String htmlLang) {
						request.setAttribute(PAGE_LANGUAGE_ATTRIBUTE, htmlLang);
					}
					modelAndView.addObject("src", (Mustache.Lambda) (frag, out) -> {
						String url = frag.execute();
						String resourceUrl = Objects.requireNonNull(resourceUrlProvider).getForLookupPath(url);
						if (StringUtils.hasLength(resourceUrl)) {
							out.write(resourceUrl);
						}
						else {
							out.write(url);
						}
					});
					// Percent-encodes the fragment body as a single URL path segment so
					// tag / category names containing reserved characters (/, spaces,
					// sub-delims) embed safely in hierarchical URLs. Wrap a triple-stache
					// inside (e.g. {{#urlPath}}{{{name}}}{{/urlPath}}) so HTML escaping
					// does not run before URL encoding.
					modelAndView.addObject("urlPath", (Mustache.Lambda) (frag, out) -> {
						String raw = frag.execute();
						out.write(UriUtils.encodePathSegment(raw, StandardCharsets.UTF_8));
					});
				}
			}
		});
	}

	/**
	 * Resolves the request locale from the language the page actually renders in, rather
	 * than from the client's {@code Accept-Language}.
	 *
	 * <p>
	 * {@code DispatcherServlet} calls this again right before rendering and applies the
	 * result with {@code response.setLocale}, which is what emits
	 * {@code Content-Language}. Sourcing it from the page keeps that header describing
	 * the response — the English tenant's pages report {@code en} — instead of echoing
	 * what the browser asked for.
	 *
	 * <p>
	 * The header is not decoration: {@code hx-boost} swaps only {@code <body>}'s inner
	 * HTML, so the response document's own {@code <html lang>} is discarded, and
	 * {@code lang-sync.js} mirrors {@code Content-Language} onto {@code <html>} to keep
	 * boosted navigation as correct as a real one. Both values come from the same
	 * {@code htmlLang} model attribute, so they cannot drift.
	 *
	 * <p>
	 * Handlers that render no view (raw markdown, RSS, sitemap) leave the attribute unset
	 * and fall back to the site's primary language.
	 */
	@Bean
	LocaleResolver localeResolver() {
		return new LocaleResolver() {
			@Override
			public Locale resolveLocale(HttpServletRequest request) {
				return request.getAttribute(PAGE_LANGUAGE_ATTRIBUTE) instanceof String pageLanguage
						? Locale.forLanguageTag(pageLanguage) : Locale.JAPANESE;
			}

			@Override
			public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response,
					@Nullable Locale locale) {
				// The language follows the requested page, so there is nothing for a
				// client to switch.
				throw new UnsupportedOperationException("The locale is derived from the page being rendered");
			}
		};
	}

}
