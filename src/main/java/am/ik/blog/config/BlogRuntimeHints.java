package am.ik.blog.config;

import am.ik.blog.BlogProps;
import am.ik.blog.counter.Counter;
import am.ik.blog.counter.IncrementRequest;
import am.ik.blog.entry.Author;
import am.ik.blog.entry.Category;
import am.ik.blog.entry.CursorPage;
import am.ik.blog.entry.Entry;
import am.ik.blog.entry.FrontMatter;
import am.ik.blog.entry.GiscusProps;
import am.ik.blog.entry.Tag;
import am.ik.blog.entry.TagAndCount;
import am.ik.blog.entry.web.CategoryCrumb;
import am.ik.blog.entry.web.LanguageToggle;
import am.ik.blog.note.CreateReaderInput;
import am.ik.blog.note.NoteDetails;
import am.ik.blog.note.NoteErrorResponse;
import am.ik.blog.note.NoteFrontMatter;
import am.ik.blog.note.NoteSummary;
import am.ik.blog.note.OAuth2Token;
import am.ik.blog.note.PasswordResetInput;
import am.ik.blog.note.ResponseMessage;
import am.ik.blog.note.SendLinkInput;
import am.ik.blog.note.SubscribeOutput;
import am.ik.blog.note.web.NoteListItem;
import am.ik.blog.site.AboutProps;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native-image runtime hints.
 *
 * <p>
 * Two groups of types are registered here:
 * <ul>
 * <li><b>JSON binding types</b> — records exchanged with the upstream Blog API and
 * Counter API via {@code RestClient}. Generic response types are fetched through
 * {@code ParameterizedTypeReference} (e.g. {@code CursorPage<Entry>},
 * {@code List<TagAndCount>}), and Spring AOT cannot always see past the anonymous
 * subclass to every element type, so each record is registered with the full set of
 * {@link MemberCategory} that Jackson may need.</li>
 * <li><b>View-model types</b> — records handed to Mustache templates via
 * {@code model.addAttribute(...)}. JMustache reads fields and invokes accessor methods
 * reflectively; registering declared methods and fields lets the template interpolation
 * keep working under native image.</li>
 * </ul>
 *
 * <p>
 * {@code @ConfigurationProperties} records ({@link BlogProps}, {@link AboutProps}, etc.)
 * are already picked up by Spring Boot's configuration-properties AOT processor, but
 * since they are also rendered by Mustache templates we register them here so every
 * nested record becomes reflectively reachable for the template engine as well.
 */
public class BlogRuntimeHints implements RuntimeHintsRegistrar {

	private static final Class<?>[] BINDING_TYPES = { Entry.class, FrontMatter.class, Author.class, Tag.class,
			Category.class, CursorPage.class, TagAndCount.class, Counter.class, IncrementRequest.class,
			OAuth2Token.class, NoteSummary.class, NoteDetails.class, NoteFrontMatter.class, NoteErrorResponse.class,
			ResponseMessage.class, CreateReaderInput.class, SendLinkInput.class, PasswordResetInput.class,
			SubscribeOutput.class };

	private static final Class<?>[] VIEW_MODEL_TYPES = { BlogProps.class, AboutProps.class, AboutProps.Profile.class,
			AboutProps.Company.class, AboutProps.Role.class, AboutProps.School.class, AboutProps.Degree.class,
			GiscusProps.class, CategoryCrumb.class, LanguageToggle.class, LanguageToggle.Link.class,
			NoteListItem.class };

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		for (Class<?> type : BINDING_TYPES) {
			hints.reflection()
				.registerType(type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS,
						MemberCategory.ACCESS_DECLARED_FIELDS);
		}
		for (Class<?> type : VIEW_MODEL_TYPES) {
			hints.reflection()
				.registerType(type, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS,
						MemberCategory.ACCESS_DECLARED_FIELDS);
		}
	}

}
