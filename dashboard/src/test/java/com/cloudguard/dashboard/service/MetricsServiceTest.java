package com.cloudguard.dashboard.service;

import com.cloudguard.dashboard.dto.FindingSubmission;
import com.cloudguard.dashboard.dto.ScanSubmission;
import com.cloudguard.dashboard.model.Source;
import com.cloudguard.dashboard.repo.FindingRepository;
import com.cloudguard.dashboard.repo.RemediationRepository;
import com.cloudguard.dashboard.repo.ScanRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:metrics-test;DB_CLOSE_DELAY=-1")
class MetricsServiceTest {

  @Autowired IngestionService ingestion;
  @Autowired MetricsService metrics;
  @Autowired FindingRepository findings;
  @Autowired ScanRunRepository scanRuns;
  @Autowired RemediationRepository remediations;

  @BeforeEach
  void clean() {
    findings.deleteAll();
    scanRuns.deleteAll();
    remediations.deleteAll();
  }

  private ScanSubmission scan(Source source, int passed, int failed, List<FindingSubmission> items) {
    return new ScanSubmission(source, passed, failed, "run", Instant.now(), items);
  }

  private FindingSubmission finding(String resource, String rule) {
    return new FindingSubmission(resource, rule, "msg", "HIGH");
  }

  /** No data must never render as 100% — those are opposite situations. */
  @Test
  void scoreIsAbsentWhenNoPolicySourceHasReported() {
    assertTrue(metrics.complianceScore().isEmpty());
  }

  @Test
  void scoreIsComputedFromOpenFindingsOverTotalChecks() {
    ingestion.ingestScan(
        scan(Source.CHECKOV, 18, 28, List.of(finding("a", "R1"), finding("b", "R2"))));
    ingestion.ingestScan(scan(Source.OPA, 0, 8, List.of(finding("c", "R3"))));

    // 3 open of (46 + 8) = 54 checks -> 94%
    assertEquals(54, metrics.totalPolicyChecks());
    assertEquals(3, metrics.openPolicyFindings());
    assertEquals(94, metrics.complianceScore().orElseThrow());
  }

  /**
   * Regression: a crashed scanner used to drop its checks out of the
   * denominator, moving the score from 94% to 63% even though nothing
   * about the real posture had changed.
   */
  @Test
  void inconclusiveRunDoesNotMoveTheScore() {
    ingestion.ingestScan(
        scan(Source.CHECKOV, 18, 28, List.of(finding("a", "R1"), finding("b", "R2"))));
    ingestion.ingestScan(scan(Source.OPA, 0, 8, List.of(finding("c", "R3"))));
    int before = metrics.complianceScore().orElseThrow();

    // Checkov crashes and reports nothing.
    ingestion.ingestScan(scan(Source.CHECKOV, 0, 0, List.of()));

    assertEquals(before, metrics.complianceScore().orElseThrow(), "a crashed scanner must not change the score");
    assertEquals(54, metrics.totalPolicyChecks(), "last completed run keeps contributing its checks");
  }

  /** Trivy is a vulnerability scanner with no pass count — it must stay out of the score. */
  @Test
  void trivyFindingsAreExcludedFromComplianceScore() {
    ingestion.ingestScan(scan(Source.CHECKOV, 10, 0, List.of()));
    ingestion.ingestScan(scan(Source.TRIVY, 0, 41, List.of(finding("image", "CVE-2026-1"))));

    assertEquals(10, metrics.totalPolicyChecks(), "Trivy must not contribute to the denominator");
    assertEquals(0, metrics.openPolicyFindings(), "Trivy findings are not policy findings");
    assertEquals(100, metrics.complianceScore().orElseThrow());
    assertEquals(1, metrics.openVulnerabilities());
  }

  @Test
  void freshnessReportsNeverRanForSourcesWithNoData() {
    ingestion.ingestScan(scan(Source.CHECKOV, 5, 0, List.of()));

    var bySource = metrics.freshness();
    assertEquals(Source.values().length, bySource.size());
    assertTrue(
        bySource.stream().filter(f -> f.source() == Source.KYVERNO).allMatch(MetricsService.SourceFreshness::neverRan));
    assertTrue(
        bySource.stream().filter(f -> f.source() == Source.CHECKOV).noneMatch(MetricsService.SourceFreshness::neverRan));
  }

  @Test
  void mttrIsAbsentUntilARemediationIsRecorded() {
    assertEquals(Optional.empty(), metrics.averageMttrSeconds());
  }

  /**
   * Regression: a record whose fix predates its trigger is impossible
   * (clock skew or a malformed timestamp) and must not be averaged in.
   * One such record previously produced a reported MTTR of -8s.
   */
  @Test
  void negativeDurationsAreExcludedFromMttr() {
    Instant now = Instant.now();
    ingestion.ingestRemediation(
        new com.cloudguard.dashboard.dto.RemediationSubmission(
            "key-good", "bucket", "rule", now.minusSeconds(30), now, "fixed"));
    // triggeredAt AFTER remediatedAt — impossible.
    ingestion.ingestRemediation(
        new com.cloudguard.dashboard.dto.RemediationSubmission(
            "key-bad", "bucket", "rule", now.plusSeconds(500), now, "bad timestamps"));

    assertEquals(1, metrics.invalidMttrRecords());
    assertEquals(
        30L,
        metrics.averageMttrSeconds().orElseThrow(),
        "the impossible record must not drag the average");
  }
}
