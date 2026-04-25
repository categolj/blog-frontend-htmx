package am.ik.blog.counter;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin REST client over the external Counter API.
 *
 * <p>
 * Two operations are exposed. {@link #increment(IncrementRequest)} bumps the entry's view
 * count and returns the new value; {@link #getCount(IncrementRequest)} returns the value
 * without incrementing (used when the caller is blacklisted or is a bot). When the API
 * returns an empty body, both methods fall back to {@code -1} so callers can render a
 * placeholder instead of crashing.
 */
@Component
public class CounterClient {

	private static final Logger logger = LoggerFactory.getLogger(CounterClient.class);

	private final RestClient restClient;

	public CounterClient(RestClient.Builder restClientBuilder, CounterApiProps props) {
		this.restClient = restClientBuilder.baseUrl(props.baseUrl()).build();
	}

	public Counter increment(IncrementRequest request) {
		try {
			Counter body = this.restClient.post()
				.uri("/counter")
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(Counter.class);
			if (body == null) {
				logger.warn("Counter increment returned empty body, using -1");
			}
			return Objects.requireNonNullElseGet(body, () -> new Counter(-1L));
		}
		catch (ResourceAccessException e) {
			return new Counter(-1L);
		}
	}

	public Counter getCount(IncrementRequest request) {
		try {
			Counter body = this.restClient.post()
				.uri("/counter?readOnly=true")
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(Counter.class);
			if (body == null) {
				logger.warn("Counter read-only returned empty body, using -1");
			}
			return Objects.requireNonNullElseGet(body, () -> new Counter(-1L));
		}
		catch (ResourceAccessException e) {
			return new Counter(-1L);
		}
	}

}
