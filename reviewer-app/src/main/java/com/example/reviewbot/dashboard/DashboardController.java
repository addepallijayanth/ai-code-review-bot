package com.example.reviewbot.dashboard;

import com.example.reviewbot.config.GitHubAppProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Operator dashboard: installations, reviews, findings and spend. Behind HTTP Basic auth. */
@Controller
public class DashboardController {

    private final DashboardService dashboard;
    private final String webUrl;

    public DashboardController(DashboardService dashboard, GitHubAppProperties github) {
        this.dashboard = dashboard;
        this.webUrl = github.webUrl();
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("stats", dashboard.load());
        model.addAttribute("format", new DashboardFormat());
        model.addAttribute("webUrl", webUrl);
        return "dashboard";
    }
}
