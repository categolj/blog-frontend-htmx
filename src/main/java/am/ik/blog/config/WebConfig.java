package am.ik.blog.config;

import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Objects;

import am.ik.blog.BlogProps;
import com.samskivert.mustache.Mustache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.springframework.web.util.UriUtils;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

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

}
