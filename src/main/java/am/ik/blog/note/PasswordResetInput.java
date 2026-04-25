package am.ik.blog.note;

import java.util.UUID;

/**
 * Request body shape for {@code POST /password_reset}. {@code resetId} is the one-time
 * UUID emailed to the reader; {@code newPassword} is the raw replacement.
 */
public record PasswordResetInput(UUID resetId, String newPassword) {
}
