# Fix circuit breaker interceptor order and failure accounting

Difficulty: Low — a Sonnet-class model is enough.

## Context / Why

`AppConfig#restClientCustomizer` registers the interceptors in the order
`[Logbook, Retryable, CircuitBreaker]` (first = outermost). Two problems follow:

1. **Failure accounting amplification** (the real defect, confirmed by test): the
   breaker sees one call per retry attempt, so `minimumNumberOfCalls=5` is filled
   by a single logical request that retries. A momentary connect failure opens
   the breaker immediately, defeating the sliding-window design
   (`slidingWindowSize=20`).
2. ~~Open-state latency~~ (retracted): the retry interceptor's default
   `RetryableIOExceptionPredicate` covers only `SocketTimeoutException` /
   `ConnectException` / `UnknownHostException` — `CallNotPermittedException` is
   NOT retried, so open-state calls already fail fast even with the old order.

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
