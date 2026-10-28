# Fix circuit breaker interceptor order and failure accounting

Difficulty: Low — a Sonnet-class model is enough.

## Context / Why

`AppConfig#restClientCustomizer` registers the interceptors in the order
`[Logbook, Retryable, CircuitBreaker]` (first = outermost). Two problems follow:

1. **Open-state latency**: when the breaker is open,
   `CircuitBreakerClientHttpRequestInterceptor` throws `CallNotPermittedException`
   (extends `IOException`). `RetryableClientHttpRequestInterceptor` sits outside it,
   treats that as a retryable I/O failure, and burns the full back-off
   (`ExponentialBackOff(1_000, 1.5)`, max elapsed 6s) before letting the exception
   propagate — every request during an outage pays ~6s for nothing.
2. **Failure accounting amplification**: the breaker sees one call per retry
   attempt, so `minimumNumberOfCalls=5` is filled by a single logical request that
   retries 5 times. A momentary network failure opens the breaker immediately,
   which defeats the sliding-window design (`slidingWindowSize=20`).

## Target

- `src/main/java/am/ik/blog/config/AppConfig.java` — reorder to
  `[Logbook, CircuitBreaker, Retryable]` so the breaker short-circuits before
  retries and records one outcome per logical request.

## Implementation steps

1. Swap the two `requestInterceptor(...)` calls in `AppConfig`.
2. Add a test that proves open-state calls fail fast (no back-off delay) — the
   breaker can be forced open by pointing `testsupport.MockServer` at a failing
   endpoint and issuing enough calls, or by driving the interceptor directly with
   a unit test on the interceptor chain. Keep it to one test; generous timing
   guard only (assert the call is NOT allowed / returns immediately, never assert
   throughput).

## Acceptance criteria

- [ ] With the breaker open, an outbound call fails within milliseconds, not
      after the retry back-off.
- [ ] One logical request records at most one failure on the breaker.
- [ ] `./mvnw spring-javaformat:apply test` passes.
