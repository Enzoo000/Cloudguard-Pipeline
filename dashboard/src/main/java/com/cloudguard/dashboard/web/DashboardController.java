package com.cloudguard.dashboard.web;

import com.cloudguard.dashboard.service.MetricsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

  private final MetricsService metrics;

  public DashboardController(MetricsService metrics) {
    this.metrics = metrics;
  }

  @GetMapping("/")
  public String dashboard(Model model) {
    model.addAttribute("score", metrics.complianceScore().orElse(null));
    model.addAttribute("openPolicyFindings", metrics.openPolicyFindings());
    model.addAttribute("totalPolicyChecks", metrics.totalPolicyChecks());
    model.addAttribute("openVulnerabilities", metrics.openVulnerabilities());
    model.addAttribute("findings", metrics.openFindings());
    model.addAttribute("freshness", metrics.freshness());
    model.addAttribute("remediations", metrics.remediationLog());
    model.addAttribute("avgMttr", metrics.averageMttrSeconds().orElse(null));
    return "dashboard";
  }
}
