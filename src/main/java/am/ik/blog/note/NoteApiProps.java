package am.ik.blog.note;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the upstream Note API (source:
 * <a href="https://github.com/categolj/note-api">note-api</a>).
 *
 * <p>
 * The Note API is protected by OAuth2 — callers authenticate by exchanging email/password
 * for a JWT at {@code POST /oauth/token}, then pass the token as a Bearer credential on
 * subsequent calls. Because every flow downstream of login carries a per-user token, no
 * global credentials live in this record.
 */
@ConfigurationProperties(prefix = "note.api")
public record NoteApiProps(@DefaultValue("http://localhost:9000") String baseUrl) {
}
