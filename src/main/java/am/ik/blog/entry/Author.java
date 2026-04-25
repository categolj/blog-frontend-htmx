package am.ik.blog.entry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public record Author(String name, @Nullable Instant date) {

	private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
		.withZone(ZoneId.of("UTC"));

	private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;

	public String displayDate() {
		return this.date == null ? "" : DISPLAY_FORMAT.format(this.date);
	}

	public String isoDate() {
		return this.date == null ? "" : ISO_FORMAT.format(this.date);
	}

}
