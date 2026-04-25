package am.ik.blog.error.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Serves {@code /forbidden} so the URL stays reachable for compatibility with the React
 * frontend. Supports content negotiation: {@code text/html} clients get the branded
 * {@code error.mustache} view, JSON clients get an
 * {@link org.springframework.boot.web.error.ErrorAttributes}-shaped body. Both paths
 * return HTTP 403 without raising an exception.
 */
@Controller
public class ForbiddenController {

	private static final HttpStatus STATUS = HttpStatus.FORBIDDEN;

	private static final String PATH = "/forbidden";

	@GetMapping(value = PATH, produces = MediaType.TEXT_HTML_VALUE)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public String forbiddenHtml(Model model) {
		model.addAttribute("status", STATUS.value());
		model.addAttribute("error", STATUS.getReasonPhrase());
		model.addAttribute("path", PATH);
		return "error";
	}

	@GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> forbiddenJson() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now().toString());
		body.put("status", STATUS.value());
		body.put("error", STATUS.getReasonPhrase());
		body.put("path", PATH);
		return ResponseEntity.status(STATUS).body(body);
	}

}
