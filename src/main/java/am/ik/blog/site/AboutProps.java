package am.ik.blog.site;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Profile data rendered on {@code /aboutme}. Sourced from {@code application.properties}
 * so the owner can tweak titles, dates, and links without a code change. The X handle is
 * intentionally not duplicated here — it lives on {@link am.ik.blog.BlogProps} and is
 * shared with the OGP/Twitter Card meta tags.
 */
@ConfigurationProperties(prefix = "blog.about")
public record AboutProps(Profile profile, List<Company> companies, List<School> schools) {

	public record Profile(String name, String title, String avatarUrl, String email, String dogBreedLabel,
			String dogBreedUrl, String dogName) {
	}

	public record Company(String name, String url, List<Role> roles) {
	}

	public record Role(String title, String period, String location) {
	}

	public record School(String name, List<Degree> degrees) {
	}

	public record Degree(String title, String period, String locationLabel, String locationUrl) {
	}

}
