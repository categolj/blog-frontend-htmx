package am.ik.blog.config;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;

import am.ik.spring.http.client.RetryableClientHttpRequestInterceptor;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerClientHttpRequestInterceptor;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerConfig;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerRegistry;
import am.ik.spring.http.client.circuitbreaker.LoggingCircuitBreakerLifecycle;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(BlogRuntimeHints.class)
public class AppConfig {

	@Bean
	public InstantSource instantSource() {
		return InstantSource.system();
	}

	/**
	 * Exposed as a bean so tests can reset the breaker state between scenarios (the
	 * breakers are keyed by request host and shared by every RestClient in the app).
	 */
	@Bean
	public CircuitBreakerRegistry circuitBreakerRegistry() {
		return CircuitBreakerRegistry.of(CircuitBreakerConfig.builder()
			.slidingWindowSize(20)
			.minimumNumberOfCalls(5)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofMinutes(1))
			.permittedNumberOfCallsInHalfOpenState(5)
			.build());
	}

	@Bean
	public RestClientCustomizer restClientCustomizer(Logbook logbook, CircuitBreakerRegistry circuitBreakerRegistry) {
		ExponentialBackOff backOff = new ExponentialBackOff(1_000, 1.5);
		backOff.setMaxElapsedTime(6_000);
		List<ClientHttpRequestInterceptor> interceptors = clientHttpRequestInterceptors(logbook, circuitBreakerRegistry,
				backOff);
		return restClientBuilder -> interceptors.forEach(restClientBuilder::requestInterceptor);
	}

	/**
	 * The interceptor order is significant: the first interceptor is the outermost. The
	 * circuit breaker must wrap the retry interceptor, so it sees ONE outcome per logical
	 * request — if the retry interceptor ran outside it, every internal retry re-entered
	 * the breaker and a single request could fill {@code minimumNumberOfCalls} on its
	 * own. It also short-circuits open-breaker calls before the retry loop can spend any
	 * back-off on them. Extracted package-visibly so the order stays unit testable.
	 */
	static List<ClientHttpRequestInterceptor> clientHttpRequestInterceptors(Logbook logbook,
			CircuitBreakerRegistry circuitBreakerRegistry, BackOff backOff) {
		return List.of(new LogbookClientHttpRequestInterceptor(logbook),
				CircuitBreakerClientHttpRequestInterceptor.builder()
					.registry(circuitBreakerRegistry)
					.lifecycle(LoggingCircuitBreakerLifecycle.builder().build())
					.build(),
				// Response-status retries (the library defaults retry 5xx for ~6s) are
				// disabled: the local entry cache serves stale content on upstream
				// failure, so burning the back-off before every fallback would delay
				// each stale response and slow the breaker's failure accounting. Only
				// the IOException predicates (timeouts, connect errors) still retry.
				new RetryableClientHttpRequestInterceptor(backOff, Set.of()));
	}

}
