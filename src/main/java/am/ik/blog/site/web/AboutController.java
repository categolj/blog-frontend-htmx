package am.ik.blog.site.web;

import am.ik.blog.site.AboutProps;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves {@code /aboutme} — a largely static profile page. Content is bound from
 * {@code blog.about.*} properties rather than hard-coded in the template, so edits to the
 * profile ship as config, not code.
 */
@Controller
public class AboutController {

	private final AboutProps aboutProps;

	public AboutController(AboutProps aboutProps) {
		this.aboutProps = aboutProps;
	}

	@GetMapping("/aboutme")
	public String aboutme(Model model) {
		model.addAttribute("pageTitle", "About Me");
		model.addAttribute("profile", this.aboutProps.profile());
		model.addAttribute("companies", this.aboutProps.companies());
		model.addAttribute("schools", this.aboutProps.schools());
		return "aboutme";
	}

}
