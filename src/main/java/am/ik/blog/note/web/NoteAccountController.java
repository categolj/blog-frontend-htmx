package am.ik.blog.note.web;

import am.ik.blog.note.NoteAccountException;
import am.ik.blog.note.NoteClient;
import am.ik.blog.note.ResponseMessage;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Public (pre-login) account-management screens: signup, password-reset request, the
 * password reset form reached from the emailed link, and the activation landing page.
 *
 * <p>
 * Kept separate from {@link NoteController} so the authenticated content paths stay
 * focused on reading notes — these handlers live entirely inside the {@code permitAll}
 * slot of the note-scoped {@code SecurityFilterChain}, paired with CSRF-protected POST
 * forms.
 *
 * <p>
 * All success paths use the POST-Redirect-Get pattern (a 302 to the same URL with a
 * boolean query flag) so browser refresh does not re-submit the form. Error paths
 * re-render the template directly so the user's typed values (e.g. email) survive the
 * round-trip.
 */
@Controller
public class NoteAccountController {

	/**
	 * Upstream route constraint for UUID path variables (reader ids, activation link ids,
	 * password-reset ids). Matches the note-api's own pattern — see
	 * {@code am.ik.note.content.web.NoteController}'s route regex for the source of
	 * truth.
	 */
	private static final String UUID_REGEX = "[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12}";

	private static final String PAGE_TITLE_SIGNUP = "Sign up";

	private static final String PAGE_TITLE_RESET = "Password reset";

	private static final String PAGE_TITLE_ACTIVATION = "Activation";

	private final NoteClient noteClient;

	public NoteAccountController(NoteClient noteClient) {
		this.noteClient = noteClient;
	}

	@GetMapping("/note/signup")
	public String signupForm(@RequestParam(name = "signedUp", required = false) @Nullable String signedUp,
			Model model) {
		populateSignup(model, "", null);
		model.addAttribute("signedUp", signedUp != null);
		return "note/signup";
	}

	@PostMapping("/note/signup")
	public Object signup(@RequestParam String email, @RequestParam String password,
			@RequestParam String confirmPassword, Model model) {
		if (!password.equals(confirmPassword)) {
			populateSignup(model, email, "パスワード(確認)が一致しません。");
			return "note/signup";
		}
		try {
			this.noteClient.createReader(email, password);
			return redirect("/note/signup?signedUp");
		}
		catch (NoteAccountException e) {
			populateSignup(model, email, e.getMessage());
			return "note/signup";
		}
	}

	@GetMapping("/note/password_reset/send_link")
	public String sendLinkForm(@RequestParam(name = "sent", required = false) @Nullable String sent, Model model) {
		populateSendLink(model, "", null);
		model.addAttribute("sent", sent != null);
		return "note/password_reset_request";
	}

	@PostMapping("/note/password_reset/send_link")
	public Object sendLink(@RequestParam String email, Model model) {
		try {
			this.noteClient.sendPasswordResetLink(email);
			return redirect("/note/password_reset/send_link?sent");
		}
		catch (NoteAccountException e) {
			populateSendLink(model, email, e.getMessage());
			return "note/password_reset_request";
		}
	}

	@GetMapping("/note/password_reset/{resetId:" + UUID_REGEX + "}")
	public String resetForm(@PathVariable String resetId, Model model) {
		populateReset(model, resetId, null);
		return "note/password_reset";
	}

	@PostMapping("/note/password_reset/{resetId:" + UUID_REGEX + "}")
	public Object reset(@PathVariable String resetId, @RequestParam String password,
			@RequestParam String confirmPassword, Model model) {
		if (!password.equals(confirmPassword)) {
			populateReset(model, resetId, "パスワード(確認)が一致しません。");
			return "note/password_reset";
		}
		try {
			this.noteClient.resetPassword(UUID.fromString(resetId), password);
			return redirect("/note/login?reset");
		}
		catch (NoteAccountException e) {
			populateReset(model, resetId, e.getMessage());
			return "note/password_reset";
		}
	}

	@GetMapping("/note/readers/{readerId:" + UUID_REGEX + "}/activations/{activationLinkId:" + UUID_REGEX + "}")
	public String activate(@PathVariable String readerId, @PathVariable String activationLinkId, Model model,
			HttpServletResponse response) {
		model.addAttribute("pageTitle", PAGE_TITLE_ACTIVATION);
		try {
			ResponseMessage result = this.noteClient.activateReader(readerId, activationLinkId);
			model.addAttribute("success", true);
			model.addAttribute("message", result.message());
		}
		catch (NoteAccountException e) {
			// The activation URL is a one-shot GET triggered by the email link; when it
			// fails (expired/unknown) we still render 200 with an error message so the
			// reader sees guidance rather than a bare error page.
			response.setStatus(HttpStatus.OK.value());
			model.addAttribute("success", false);
			model.addAttribute("message", e.getMessage());
		}
		return "note/activation";
	}

	private static void populateSignup(Model model, String email, @Nullable String error) {
		model.addAttribute("pageTitle", PAGE_TITLE_SIGNUP);
		model.addAttribute("email", email);
		model.addAttribute("error", error);
		model.addAttribute("signedUp", false);
	}

	private static void populateSendLink(Model model, String email, @Nullable String error) {
		model.addAttribute("pageTitle", PAGE_TITLE_RESET);
		model.addAttribute("email", email);
		model.addAttribute("error", error);
		model.addAttribute("sent", false);
	}

	private static void populateReset(Model model, String resetId, @Nullable String error) {
		model.addAttribute("pageTitle", PAGE_TITLE_RESET);
		model.addAttribute("resetId", resetId);
		model.addAttribute("error", error);
	}

	/**
	 * Builds a redirect response that does not expose model attributes as query
	 * parameters. Required because {@code WebConfig}'s postHandle interceptor seeds
	 * default view attributes (e.g. {@code htmlLang}, {@code bundledJs}) that the plain
	 * {@code redirect:…} prefix would otherwise append to the URL.
	 */
	private static ModelAndView redirect(String url) {
		RedirectView view = new RedirectView(url);
		view.setExposeModelAttributes(false);
		return new ModelAndView(view);
	}

}
