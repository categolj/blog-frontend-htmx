package am.ik.blog.config;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;

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

	@Bean
	public RestClientCustomizer restClientCustomizer(Logbook logbook) {
		ExponentialBackOff backOff = new ExponentialBackOff(1_000, 1.5);
		backOff.setMaxElapsedTime(6_000);
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.builder()
			.slidingWindowSize(20)
			.minimumNumberOfCalls(5)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofMinutes(1))
			.permittedNumberOfCallsInHalfOpenState(5)
			.build());
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
				new RetryableClientHttpRequestInterceptor(backOff));
	}

}
