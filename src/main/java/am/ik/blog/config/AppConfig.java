package am.ik.blog.config;

import java.time.Duration;
import java.time.InstantSource;

import am.ik.spring.http.client.RetryableClientHttpRequestInterceptor;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerClientHttpRequestInterceptor;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerConfig;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerRegistry;
import am.ik.spring.http.client.circuitbreaker.LoggingCircuitBreakerLifecycle;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
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
		return restClientBuilder -> restClientBuilder
			.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook))
			.requestInterceptor(new RetryableClientHttpRequestInterceptor(backOff))
			.requestInterceptor(CircuitBreakerClientHttpRequestInterceptor.builder()
				.registry(circuitBreakerRegistry)
				.lifecycle(LoggingCircuitBreakerLifecycle.builder().build())
				.build());
	}

}
