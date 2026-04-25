package am.ik.blog.note.web;

import am.ik.blog.htmx.Htmx;
import am.ik.blog.markdown.MarkdownRenderer;
import am.ik.blog.note.NoteAuthentication;
import am.ik.blog.note.NoteClient;
import am.ik.blog.note.NoteDetails;
import am.ik.blog.note.NoteNotSubscribedException;
import am.ik.blog.note.NoteSessionExpiredException;
import am.ik.blog.note.NoteSummary;
import am.ik.blog.note.SubscribeOutput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class NoteController {

	/**
	 * Matches the "はじめるSpring Boot 3" magazine label used by the React reference — the
	 * list page currently renders a single known collection, so the title stays in-code
	 * rather than flowing in from the upstream.
	 */
	private static final String MAGAZINE_TITLE = "はじめるSpring Boot 3";

	/**
	 * UUID pattern for {@code noteId} path variables — matches the upstream's routing.
	 */
	private static final String UUID_REGEX = "[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12}";

	private final NoteClient noteClient;

	private final MarkdownRenderer markdownRenderer;

	public NoteController(NoteClient noteClient, MarkdownRenderer markdownRenderer) {
		this.noteClient = noteClient;
		this.markdownRenderer = markdownRenderer;
	}

	@GetMapping("/note/login")
	public String loginForm(@RequestParam(name = "error", required = false) @Nullable String error,
			@RequestParam(name = "logout", required = false) @Nullable String logout,
			@RequestParam(name = "reset", required = false) @Nullable String reset, Model model) {
		model.addAttribute("pageTitle", "Login");
		model.addAttribute("loginError", error != null);
		model.addAttribute("loggedOut", logout != null);
		model.addAttribute("passwordReset", reset != null);
		return "note/login";
	}

	@GetMapping("/notes")
	public Object list(Authentication authentication, Model model, HttpServletRequest request) {
		NoteAuthentication note = requireNoteAuthentication(authentication);
		List<NoteSummary> notes;
		try {
			notes = this.noteClient.findAll(note.getAccessToken());
		}
		catch (NoteSessionExpiredException e) {
			return reLogin(request);
		}
		long subscribedCount = notes.stream().filter(NoteSummary::subscribed).count();
		model.addAttribute("pageTitle", MAGAZINE_TITLE);
		model.addAttribute("magazineTitle", MAGAZINE_TITLE);
		model.addAttribute("username", note.getName());
		model.addAttribute("notes", notes.stream().map(NoteListItem::from).toList());
		model.addAttribute("totalCount", notes.size());
		model.addAttribute("subscribedCount", subscribedCount);
		if (Htmx.isPartial(request)) {
			return "fragments/note-list";
		}
		return "note/list";
	}

	@GetMapping("/notes/{entryId:\\d+}")
	public Object detail(@PathVariable Long entryId, Authentication authentication, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		NoteAuthentication note = requireNoteAuthentication(authentication);
		Optional<NoteDetails> found;
		try {
			found = this.noteClient.findByEntryId(entryId, note.getAccessToken());
		}
		catch (NoteSessionExpiredException e) {
			return reLogin(request);
		}
		catch (NoteNotSubscribedException e) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			model.addAttribute("pageTitle", "Not subscribed");
			model.addAttribute("message", e.getMessage());
			model.addAttribute("noteUrl", e.noteUrl());
			return "note/not-subscribed";
		}
		if (found.isEmpty()) {
			response.setStatus(HttpStatus.NOT_FOUND.value());
			model.addAttribute("status", HttpStatus.NOT_FOUND.value());
			model.addAttribute("error", HttpStatus.NOT_FOUND.getReasonPhrase());
			model.addAttribute("path", request.getRequestURI());
			model.addAttribute("pageTitle", "Not Found");
			return "error";
		}
		NoteDetails details = found.get();
		model.addAttribute("note", details);
		model.addAttribute("contentHtml", this.markdownRenderer.render(details.content()));
		model.addAttribute("pageTitle", details.title());
		if (Htmx.isPartial(request)) {
			return "fragments/note-detail";
		}
		return "note/detail";
	}

	/**
	 * Subscribes the reader to the note referenced by {@code noteId}. Modelled as a
	 * {@code GET} rather than a {@code POST} so the URL can be opened from a plain email
	 * link (same pattern as the activation landing page) — the upstream subscribe
	 * operation is idempotent, so repeated GETs (browser refresh, prefetcher) are safe
	 * and simply report "already subscribed".
	 *
	 * <p>
	 * Unauthenticated requests are routed through Spring Security's form login and return
	 * here via the saved-request mechanism; the upstream's 401 maps to our own
	 * {@link NoteSessionExpiredException} to force re-login when a stale JWT is still
	 * cached in the session.
	 */
	@GetMapping("/notes/{noteId:" + UUID_REGEX + "}/subscribe")
	public Object subscribe(@PathVariable String noteId, Authentication authentication, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		NoteAuthentication note = requireNoteAuthentication(authentication);
		Optional<SubscribeOutput> result;
		try {
			result = this.noteClient.subscribe(UUID.fromString(noteId), note.getAccessToken());
		}
		catch (NoteSessionExpiredException e) {
			return reLogin(request);
		}
		model.addAttribute("pageTitle", "Subscribe");
		if (result.isEmpty()) {
			response.setStatus(HttpStatus.NOT_FOUND.value());
			model.addAttribute("notFound", true);
			return "note/subscribe-result";
		}
		SubscribeOutput output = result.get();
		model.addAttribute("notFound", false);
		// Upstream's `subscribed` flag means "was already subscribed" — rename for the
		// template so the wording stays intuitive.
		model.addAttribute("alreadySubscribed", output.subscribed());
		model.addAttribute("entryId", output.entryId());
		return "note/subscribe-result";
	}

	/**
	 * Invalidate the Spring Security session and redirect the reader back through login
	 * when the upstream rejects our JWT. Returns a {@link RedirectView} with
	 * {@code exposeModelAttributes=false} so the default {@code redirect:…} prefix does
	 * not append the interceptor-supplied view attributes (e.g. {@code htmlLang},
	 * {@code bundledJs}) onto the login URL as query parameters.
	 */
	private static ModelAndView reLogin(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		RedirectView view = new RedirectView("/note/login?error");
		view.setExposeModelAttributes(false);
		return new ModelAndView(view);
	}

	/**
	 * Our {@code /notes} endpoints are mounted inside the note-scoped security filter
	 * chain which requires an authenticated request, so a plain cast would suffice — but
	 * guard with an explicit check to fail loudly if the wiring ever drifts (e.g. a
	 * different AuthenticationProvider started minting its own tokens).
	 */
	private static NoteAuthentication requireNoteAuthentication(@Nullable Authentication authentication) {
		if (authentication instanceof NoteAuthentication note) {
			return note;
		}
		throw new IllegalStateException("Expected a NoteAuthentication but got "
				+ (authentication == null ? "null" : authentication.getClass().getName()));
	}

}
