package com.cloudguard.dashboard.web;

import com.cloudguard.dashboard.dto.RemediationSubmission;
import com.cloudguard.dashboard.dto.ScanResult;
import com.cloudguard.dashboard.dto.ScanSubmission;
import com.cloudguard.dashboard.service.IngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class IngestionController {

  private final IngestionService ingestion;

  public IngestionController(IngestionService ingestion) {
    this.ingestion = ingestion;
  }

  @PostMapping("/scans")
  public ScanResult submitScan(@RequestBody ScanSubmission submission) {
    return ingestion.ingestScan(submission);
  }

  @PostMapping("/remediations")
  public ResponseEntity<Map<String, Object>> submitRemediation(
      @RequestBody RemediationSubmission submission) {
    boolean stored = ingestion.ingestRemediation(submission);
    return ResponseEntity.ok(
        Map.of(
            "stored", stored,
            "sourceKey", submission.sourceKey(),
            "note", stored ? "recorded" : "already ingested, ignored"));
  }
}
