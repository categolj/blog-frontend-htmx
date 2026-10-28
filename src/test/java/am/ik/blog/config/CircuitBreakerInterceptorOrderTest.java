package am.ik.blog.config;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.spring.http.client.circuitbreaker.CircuitBreaker;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerConfig;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.util.backoff.ExponentialBackOff;
import org.zalando.logbook.Logbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The circuit breaker must see ONE outcome per logical request. It therefore has to wrap
 * the retry interceptor, not the other way around: with the retry interceptor outside,
 * every internal retry re-enters the breaker and a single request can fill
 * {@code minimumNumberOfCalls} on its own, opening the breaker on momentary connect
 * failures.
 */
class CircuitBreakerInterceptorOrderTest {

	@Test
	void oneLogicalRequestRecordsAtMostOneBreakerFailure() throws IOException {
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.builder().build());
		// A handful of 1ms retries instead of the production ~6s back-off.
		ExponentialBackOff backOff = new ExponentialBackOff(1, 1.0);
		backOff.setMaxElapsedTime(50);
		List<ClientHttpRequestInterceptor> interceptors = AppConfig
			.clientHttpRequestInterceptors(Logbook.builder().build(), registry, backOff);
		AtomicInteger transportCalls = new AtomicInteger();
		ClientHttpRequestExecution transport = (request, body) -> {
			transportCalls.incrementAndGet();
			throw new ConnectException("connection refused");
		};
		// Fold the interceptor list the same way RestClient does: the first
		// interceptor becomes the outermost.
		ClientHttpRequestExecution chain = transport;
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			ClientHttpRequestInterceptor interceptor = interceptors.get(i);
			ClientHttpRequestExecution next = chain;
			chain = (request, body) -> interceptor.intercept(request, body, next);
		}
		MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET,
				URI.create("http://upstream.test/entries"));
		ClientHttpRequestExecution outermost = chain;
		assertThrows(ConnectException.class, () -> outermost.execute(request, new byte[0]));
		// One logical call (however many internal retries it took) counts as one
		// failure on the breaker.
		CircuitBreaker breaker = registry.circuitBreaker("upstream.test");
		assertThat(breaker.metrics().numberOfFailedCalls()).isEqualTo(1);
	}

}
