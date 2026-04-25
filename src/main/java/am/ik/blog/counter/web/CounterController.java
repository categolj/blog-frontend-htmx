package am.ik.blog.counter.web;

import am.ik.blog.counter.Counter;
import am.ik.blog.counter.CounterApiProps;
import am.ik.blog.counter.CounterClient;
import am.ik.blog.counter.IncrementRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Exposes an HTMX-friendly endpoint for the per-entry view counter.
 *
 * <p>
 * POST is used deliberately — a GET would let browser prefetch, proxy caches, and search
 * crawlers inflate the count. Bots (by {@code User-Agent}) and blacklisted IPs never
 * increment the counter; blacklisted IPs still get the current value so local development
 * can see the number without polluting real analytics.
 */
@Controller
public class CounterController {

	private static final String UNAVAILABLE = "—";

	private final CounterClient counterClient;

	private final CounterApiProps props;

	public CounterController(CounterClient counterClient, CounterApiProps props) {
		this.counterClient = counterClient;
		this.props = props;
	}

	@PostMapping(path = "/counter/{entryId:\\d+}")
	public String postCounter(@PathVariable Long entryId,
			@RequestHeader(name = HttpHeaders.USER_AGENT, required = false) @Nullable String userAgent,
			HttpServletRequest request, Model model) {
		Counter counter = resolveCounter(entryId, userAgent, request);
		model.addAttribute("count", format(counter.counter()));
		return "fragments/counter";
	}

	private Counter resolveCounter(Long entryId, @Nullable String userAgent, HttpServletRequest request) {
		if (userAgent != null && userAgent.toLowerCase(Locale.ROOT).contains("bot")) {
			return new Counter(0L);
		}
		IncrementRequest incrementRequest = new IncrementRequest(entryId);
		String ip = getIpAddress(request);
		if (ip != null && this.props.ipBlackList().contains(ip)) {
			return this.counterClient.getCount(incrementRequest);
		}
		return this.counterClient.increment(incrementRequest);
	}

	private static String format(long count) {
		return count < 0 ? UNAVAILABLE : Long.toString(count);
	}

	@Nullable private static String getIpAddress(HttpServletRequest request) {
		String xForwardedFor = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(xForwardedFor)) {
			int comma = xForwardedFor.indexOf(',');
			return (comma < 0 ? xForwardedFor : xForwardedFor.substring(0, comma)).trim();
		}
		return request.getRemoteAddr();
	}

}
