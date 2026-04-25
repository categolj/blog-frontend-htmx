package am.ik.blog.note;

/**
 * Response body shape of {@code POST /notes/{noteId}/subscribe}.
 *
 * <p>
 * Important: the {@code subscribed} flag reflects the state <i>before</i> this call — the
 * upstream maps it to {@code SubscriptionStatus#isAlreadySubscribed}, so {@code
 * true} means "was already subscribed (no state change)" and {@code false} means "newly
 * subscribed by this request". The accompanying {@code entryId} is what the UI uses to
 * redirect the reader to the actual note content.
 */
public record SubscribeOutput(Long entryId, boolean subscribed) {
}
