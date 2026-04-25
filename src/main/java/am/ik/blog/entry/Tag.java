package am.ik.blog.entry;

import org.jspecify.annotations.Nullable;

public record Tag(String name, @Nullable String version) {

	public Tag(String name) {
		this(name, null);
	}

}
