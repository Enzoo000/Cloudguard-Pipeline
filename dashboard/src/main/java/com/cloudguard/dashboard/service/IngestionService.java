package com.cloudguard.dashboard.service;

import com.cloudguard.dashboard.dto.*;
import com.cloudguard.dashboard.model.*;
import com.cloudguard.dashboard.repo.FindingRepository;
import com.cloudguard.dashboard.repo.RemediationRepository;
import com.cloudguard.dashboard.repo.ScanRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class IngestionService {

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

  private final ScanRunRepository scanRuns;
  private final FindingRepository findings;
  private final RemediationRepository remediations;

  public IngestionService(
      ScanRunRepository scanRuns, FindingRepository findings, RemediationRepository remediations) {
    this.scanRuns = scanRuns;
    this.findings = findings;
    this.remediations = remediations;
  }

  @Transactional
  public ScanResult ingestScan(ScanSubmission submission) {
    Source source = submission.source();
    Instant ranAt = submission.ranAt() != null ? submission.ranAt() : Instant.now();

    ScanRun run =
        scanRuns.save(
            new ScanRun(source, submission.passed(), submission.failed(), submission.runRef(), ranAt));

    // Upsert every reported finding, tracking what this run actually saw.
    Set<String> seen = new HashSet<>();
    for (FindingSubmission submitted : submission.findingsOrEmpty()) {
      String fingerprint = Finding.fingerprint(source, submitted.resource(), submitted.ruleId());
      seen.add(fingerprint);

      findings
          .findByFingerprint(fingerprint)
          .ifPresentOrElse(
              existing -> {
                existing.markSeen(submitted.message(), submitted.severity());
                findings.save(existing);
              },
              () ->
                  findings.save(
                      new Finding(
                          source,
                          submitted.resource(),
                          submitted.ruleId(),
                          submitted.message(),
                          submitted.severity())));
    }

    // THE GUARD. A run that reported zero checks tells us nothing about
    // the state of the world — the scanner crashed, timed out, or never
    // ran. Sweeping on it would resolve every open finding and render a
    // perfect compliance score, so "the scanner is broken" would look
    // identical to "everything is fine". Refuse to sweep instead.
    if (run.status == RunStatus.INCONCLUSIVE) {
      log.warn(
          "Scan run for {} reported 0 passed and 0 failed checks — recorded as INCONCLUSIVE, "
              + "resolution sweep skipped so existing findings are left untouched.",
          source);
      return new ScanResult(
          run.status,
          seen.size(),
          0,
          true,
          "Run reported no checks; existing findings left untouched.");
    }

    // Scoped sweep: only this source's findings. A Checkov run knows
    // nothing about Trivy's or Kyverno's findings, so it must never
    // resolve them.
    int resolved = 0;
    List<Finding> openForSource = findings.findBySourceAndStatus(source, FindingStatus.OPEN);
    for (Finding open : openForSource) {
      if (!seen.contains(open.fingerprint)) {
        open.resolve();
        findings.save(open);
        resolved++;
      }
    }

    log.info(
        "Ingested {} run {}: {} findings reported, {} resolved ({} passed / {} failed)",
        source,
        submission.runRef(),
        seen.size(),
        resolved,
        submission.passed(),
        submission.failed());

    return new ScanResult(run.status, seen.size(), resolved, false, null);
  }

  /**
   * Idempotent on sourceKey — the sync script re-reads the same S3
   * objects on every run, and a fix should be counted once.
   */
  @Transactional
  public boolean ingestRemediation(RemediationSubmission submitted) {
    if (remediations.existsBySourceKey(submitted.sourceKey())) {
      return false;
    }
    remediations.save(
        new Remediation(
            submitted.sourceKey(),
            submitted.resource(),
            submitted.rule(),
            submitted.triggeredAt(),
            submitted.remediatedAt(),
            submitted.details()));
    return true;
  }
}
