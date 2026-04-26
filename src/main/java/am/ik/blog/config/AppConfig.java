package am.ik.blog.config;

import java.time.Duration;
import java.time.InstantSource;

import am.ik.spring.http.client.RetryableClientHttpRequestInterceptor;
import am.ik.spring.http.client.circuitbreaker.CircuitBreaker;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerClientHttpRequestInterceptor;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerConfig;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerLifecycle;
import am.ik.spring.http.client.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
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
		backOff.setMaxElapsedTime(12_000);
		CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.builder()
			.slidingWindowSize(20)
			.minimumNumberOfCalls(5)
			.failureRateThreshold(50f)
			.waitDurationInOpenState(Duration.ofMinutes(1))
			.permittedNumberOfCallsInHalfOpenState(5)
			.build());
		Logger logger = LoggerFactory.getLogger(AppConfig.class);
		return restClientBuilder -> restClientBuilder
			.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook))
			.requestInterceptor(new RetryableClientHttpRequestInterceptor(backOff))
			.requestInterceptor(CircuitBreakerClientHttpRequestInterceptor.builder()
				.registry(circuitBreakerRegistry)
				.lifecycle(new CircuitBreakerLifecycle() {
					@Override
					public void onFailure(CircuitBreaker circuitBreaker, HttpRequest request,
							ResponseOrException responseOrException) {
						logger.info("Circuit breaker request failed: {} {}", request.getMethod(), request.getURI());
					}

					@Override
					public void onCallNotPermitted(CircuitBreaker circuitBreaker, HttpRequest request) {
						logger.info("Circuit breaker request not permitted: {} {}", request.getMethod(),
								request.getURI());
					}
				})
				.build());
	}

}
