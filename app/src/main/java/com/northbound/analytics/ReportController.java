package com.northbound.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;

@RestController
public class ReportController {

  @Value("${app.secret}")
  private String secret;

  @Value("${app.exportsDir}")
  private String exportsDir;

  private final ReportService reportService;

  public ReportController(ReportService reportService) {
    this.reportService = reportService;
  }

  /**
   * SEEDED FLAW: see ReportService.searchByCustomer for the SQL
   * injection. This endpoint just wires an auth-gated request to it.
   */
  @GetMapping("/reports")
  public List<Report> reports(@RequestHeader("x-auth-token") String token,
                               @RequestParam("customerId") String customerId) {
    AuthUtil.assertValid(secret, token);
    return reportService.searchByCustomer(customerId);
  }

  /**
   * SEEDED FLAW (path traversal): the "**" wildcard mapping passes the
   * entire trailing path straight through to the filesystem with no
   * sanitization — including any "../" segments. This is the same root
   * cause behind real Spring path-traversal CVEs that used a wildcard
   * resource-serving mapping: a single-segment @PathVariable would have
   * blocked "/" in the value, but "**" doesn't.
   *
   * GET /exports/../src/main/resources/application.properties escapes
   * the intended directory and reads this app's own hardcoded secret.
   */
  @GetMapping("/exports/**")
  public Resource export(@RequestHeader("x-auth-token") String token,
                          HttpServletRequest request) {
    AuthUtil.assertValid(secret, token);
    String prefix = "/exports/";
    String requestedPath = request.getRequestURI().substring(prefix.length());
    File file = new File(exportsDir, requestedPath);
    return new FileSystemResource(file);
  }
}
