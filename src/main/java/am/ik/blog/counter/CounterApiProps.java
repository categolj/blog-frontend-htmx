package am.ik.blog.counter;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the external Counter API.
 *
 * <p>
 * The counter service records per-entry view counts. Requests coming from an IP on
 * {@code ipBlackList} are served in read-only mode (the count is returned but not
 * incremented), which is how local development avoids inflating real counters.
 *
 * <p>
 * When {@code accessToken} is set, every upstream call carries it as a Bearer credential.
 * Leave it empty for a counter service that does not require auth.
 */
@ConfigurationProperties(prefix = "counter.api")
public record CounterApiProps(@DefaultValue("http://localhost:8888") String baseUrl,
		@DefaultValue("") String accessToken, @DefaultValue( {
		}) List<String> ipBlackList){
}
