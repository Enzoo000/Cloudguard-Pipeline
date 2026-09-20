package com.cloudguard.dashboard.service;

import com.cloudguard.dashboard.dto.FindingSubmission;
import com.cloudguard.dashboard.dto.ScanResult;
import com.cloudguard.dashboard.dto.ScanSubmission;
import com.cloudguard.dashboard.model.FindingStatus;
import com.cloudguard.dashboard.model.RunStatus;
import com.cloudguard.dashboard.model.Source;
import com.cloudguard.dashboard.repo.FindingRepository;
import com.cloudguard.dashboard.repo.ScanRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:ingestion-test;DB_CLOSE_DELAY=-1")
class IngestionServiceTest {

  @Autowired IngestionService ingestion;
  @Autowired FindingRepository findings;
  @Autowired ScanRunRepository scanRuns;

  @BeforeEach
  void clean() {
    findings.deleteAll();
    scanRuns.deleteAll();
  }

  private ScanSubmission scan(Source source, int passed, int failed, List<FindingSubmission> items) {
    return new ScanSubmission(source, passed, failed, "test-run", Instant.now(), items);
  }

  private FindingSubmission finding(String resource, String rule) {
    return new FindingSubmission(resource, rule, "message for " + rule, "HIGH");
  }

  /**
   * The dangerous path. A scanner that crashes and reports nothing must
   * never resolve existing findings — otherwise a broken pipeline
   * renders as a perfect compliance score.
   */
  @Test
  void emptyRunIsInconclusiveAndNeverResolvesExistingFindings() {
    ingestion.ingestScan(
        scan(Source.CHECKOV, 5, 2, List.of(finding("aws_s3_bucket.x", "CKV_1"), finding("aws_iam_role.y", "CKV_2"))));
    assertEquals(2, findings.countByStatus(FindingStatus.OPEN));

    // Scanner produced nothing at all.
    ScanResult result = ingestion.ingestScan(scan(Source.CHECKOV, 0, 0, List.of()));

    assertEquals(RunStatus.INCONCLUSIVE, result.status());
    assertTrue(result.sweepSkipped(), "sweep must be skipped for a run that reported no checks");
    assertEquals(0, result.findingsResolved());
    assertEquals(
        2,
        findings.countByStatus(FindingStatus.OPEN),
        "existing findings must survive an inconclusive run");
  }

  /** A genuinely clean run — checks ran, none failed — should resolve. */
  @Test
  void cleanRunResolvesFindingsThatNoLongerAppear() {
    ingestion.ingestScan(scan(Source.CHECKOV, 5, 1, List.of(finding("aws_s3_bucket.x", "CKV_1"))));
    assertEquals(1, findings.countByStatus(FindingStatus.OPEN));

    ScanResult result = ingestion.ingestScan(scan(Source.CHECKOV, 6, 0, List.of()));

    assertEquals(RunStatus.COMPLETE, result.status());
    assertFalse(result.sweepSkipped());
    assertEquals(1, result.findingsResolved());
    assertEquals(0, findings.countByStatus(FindingStatus.OPEN));
  }

  /** One scanner must never resolve another scanner's findings. */
  @Test
  void sweepIsScopedToItsOwnSource() {
    ingestion.ingestScan(scan(Source.CHECKOV, 3, 1, List.of(finding("aws_iam_role.y", "CKV_2"))));
    ingestion.ingestScan(scan(Source.KYVERNO, 4, 1, List.of(finding("Deployment/app", "disallow-latest-tag"))));
    assertEquals(2, findings.countByStatus(FindingStatus.OPEN));

    // A clean Checkov run knows nothing about Kyverno.
    ingestion.ingestScan(scan(Source.CHECKOV, 4, 0, List.of()));

    assertEquals(0, findings.countBySourceAndStatus(Source.CHECKOV, FindingStatus.OPEN));
    assertEquals(
        1,
        findings.countBySourceAndStatus(Source.KYVERNO, FindingStatus.OPEN),
        "Kyverno's finding must be untouched by a Checkov run");
  }

  /** Re-running CI on the same flaw must not accumulate duplicate rows. */
  @Test
  void repeatedRunsDeduplicateOnFingerprint() {
    for (int i = 0; i < 5; i++) {
      ingestion.ingestScan(scan(Source.CHECKOV, 5, 1, List.of(finding("aws_s3_bucket.x", "CKV_1"))));
    }
    assertEquals(1, findings.count(), "same flaw seen 5 times should be one row");
    assertEquals(1, findings.countByStatus(FindingStatus.OPEN));
  }

  /** A flaw that comes back after being fixed must reopen, not stay resolved. */
  @Test
  void reappearingFindingReopens() {
    ingestion.ingestScan(scan(Source.CHECKOV, 5, 1, List.of(finding("aws_s3_bucket.x", "CKV_1"))));
    ingestion.ingestScan(scan(Source.CHECKOV, 6, 0, List.of()));
    assertEquals(0, findings.countByStatus(FindingStatus.OPEN));

    ingestion.ingestScan(scan(Source.CHECKOV, 5, 1, List.of(finding("aws_s3_bucket.x", "CKV_1"))));

    assertEquals(1, findings.countByStatus(FindingStatus.OPEN));
    assertEquals(1, findings.count(), "reopening must reuse the existing row, not insert a new one");
  }
}
