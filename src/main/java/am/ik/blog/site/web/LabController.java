package am.ik.blog.site.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the browser-only tools under {@code /lab}. Everything here runs client-side —
 * the controller only picks the right template and sets the page title. No upstream API
 * calls.
 */
@Controller
public class LabController {

	@GetMapping("/lab")
	public String index(Model model) {
		model.addAttribute("pageTitle", "Lab");
		return "lab/index";
	}

	@GetMapping("/lab/mov-to-gif")
	public String movToGif(Model model) {
		model.addAttribute("pageTitle", "MOV to GIF Converter");
		return "lab/mov-to-gif";
	}

}
