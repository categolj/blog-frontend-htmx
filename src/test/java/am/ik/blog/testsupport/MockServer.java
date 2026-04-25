package am.ik.blog.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Generic in-process HTTP mock server backed by JDK's {@link HttpServer}. Stub by HTTP
 * method and path; responses are matched on exact decoded path equality.
 *
 * <p>
 * Usage: <pre>
 * private static final MockServer mockApi = MockServer.start();
 *
 * &#64;AfterAll static void stopMockApi() { mockApi.close(); }
 * &#64;DynamicPropertySource static void props(DynamicPropertyRegistry r) {
 *   r.add("blog.api.base-url", mockApi::baseUrl);
 * }
 * &#64;BeforeEach void resetRoutes() { mockApi.reset(); }
 * </pre>
 */
public final class MockServer implements AutoCloseable {

	private final HttpServer server;

	private final Map<String, MockResponse> routes = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

	private final Map<String, String> lastAuthorization = new ConcurrentHashMap<>();

	@Nullable private volatile String lastAuthorizationHeader;

	private MockServer(HttpServer server) {
		this.server = server;
	}

	/** Starts a new mock server bound to a random loopback port. */
	public static MockServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			MockServer mock = new MockServer(server);
			server.createContext("/", mock::dispatch);
			server.start();
			return mock;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/**
	 * Removes all registered stubs and resets request counters. Call from
	 * {@code @BeforeEach}.
	 */
	public void reset() {
		this.routes.clear();
		this.requestCounts.clear();
		this.lastAuthorization.clear();
		this.lastAuthorizationHeader = null;
	}

	public void stubGet(String path, int status, String body, String contentType) {
		this.routes.put("GET " + path, new MockResponse(status, body, contentType, Map.of()));
	}

	/** Convenience stub for JSON responses with HTTP 200. */
	public void stubGetJson(String path, String body) {
		stubGet(path, 200, body, "application/json");
	}

	/**
	 * JSON stub that also sets an arbitrary {@code Last-Modified} header, which lets
	 * conditional-request tests assert the value that would be echoed back to the
	 * browser.
	 */
	public void stubGetJson(String path, String body, Map<String, String> headers) {
		this.routes.put("GET " + path, new MockResponse(200, body, "application/json", Map.copyOf(headers)));
	}

	/** Convenience stub returning 404 with an empty body. */
	public void stubGetNotFound(String path) {
		stubGet(path, 404, "", "text/plain");
	}

	public void stubPost(String path, int status, String body, String contentType) {
		this.routes.put("POST " + path, new MockResponse(status, body, contentType, Map.of()));
	}

	/**
	 * Convenience stub for JSON POST responses with HTTP 200. The {@code path} may
	 * include a query string (e.g. {@code "/?readOnly=true"}) to distinguish between
	 * variants of the same endpoint.
	 */
	public void stubPostJson(String path, String body) {
		stubPost(path, 200, body, "application/json");
	}

	/**
	 * Stubs a HEAD response with no body. Useful for conditional-request scenarios: set
	 * status to 304 to simulate the upstream saying "not modified", or 200 to force the
	 * BFF down the full GET path.
	 */
	public void stubHead(String path, int status) {
		this.routes.put("HEAD " + path, new MockResponse(status, "", "application/json", Map.of()));
	}

	/**
	 * Returns the number of requests received for the given method+path. Lets tests
	 * assert that a specific upstream call was (or was not) made — e.g. that a
	 * conditional 304 short-circuited the full GET.
	 */
	public int requestCount(String method, String path) {
		AtomicInteger counter = this.requestCounts.get(method.toUpperCase(Locale.ROOT) + " " + path);
		return counter == null ? 0 : counter.get();
	}

	/**
	 * Returns the {@code Authorization} header of the most recent request to arrive at
	 * the mock, or {@code null} if none was sent.
	 */
	@Nullable public String lastAuthorization() {
		return this.lastAuthorizationHeader;
	}

	/**
	 * Returns the {@code Authorization} header recorded for the last request to the given
	 * path, or {@code null} if that path has not been hit yet.
	 */
	@Nullable public String lastAuthorizationFor(String path) {
		return this.lastAuthorization.get(path);
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void dispatch(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
			String decodedPath = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
			String rawQuery = exchange.getRequestURI().getRawQuery();
			// Path-only key preserves the original contract (stubs registered without a
			// query match any query). The full key lets callers register query-specific
			// stubs (e.g. "POST /?readOnly=true" vs. "POST /").
			String pathKey = method + " " + decodedPath;
			String fullKey = rawQuery == null ? pathKey : pathKey + "?" + rawQuery;
			this.requestCounts.computeIfAbsent(fullKey, k -> new AtomicInteger()).incrementAndGet();
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			if (auth != null) {
				this.lastAuthorizationHeader = auth;
				this.lastAuthorization.put(decodedPath, auth);
			}
			MockResponse response = this.routes.get(fullKey);
			if (response == null) {
				response = this.routes.get(pathKey);
			}
			if (response == null) {
				byte[] bytes = ("no stub for " + method + " "
						+ (rawQuery == null ? decodedPath : decodedPath + "?" + rawQuery))
					.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(404, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", response.contentType());
			response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
			// HEAD responses and 304 never carry a body — use -1 so HttpServer emits no
			// Content-Length and the client won't block waiting on body bytes.
			boolean bodiless = "HEAD".equals(method) || response.status() == 304 || response.body().isEmpty();
			byte[] bytes = bodiless ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), bodiless ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			}
		}
		finally {
			exchange.close();
		}
	}

	private record MockResponse(int status, String body, String contentType, Map<String, String> headers) {
	}

}
