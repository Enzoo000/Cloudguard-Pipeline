package com.cloudguard.dashboard.service;

import com.cloudguard.dashboard.model.*;
import com.cloudguard.dashboard.repo.FindingRepository;
import com.cloudguard.dashboard.repo.RemediationRepository;
import com.cloudguard.dashboard.repo.ScanRunRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class MetricsService {

  /** A source with no run in this long is called out as stale on the page. */
  private static final Duration STALE_AFTER = Duration.ofHours(24);

  private final ScanRunRepository scanRuns;
  private final FindingRepository findings;
  private final RemediationRepository remediations;

  public MetricsService(
      ScanRunRepository scanRuns, FindingRepository findings, RemediationRepository remediations) {
    this.scanRuns = scanRuns;
    this.findings = findings;
    this.remediations = remediations;
  }

  /**
   * Compliance is computed from policy scanners only. Trivy reports CVEs
   * with no "checks passed" count, so folding it in would mean inventing
   * a denominator — its findings are surfaced separately instead.
   *
   * Returns empty when no policy source has reported a conclusive run.
   * That case must not render as 100%: "no data" and "everything passes"
   * are opposite situations and the page says so explicitly.
   */
  public Optional<Integer> complianceScore() {
    int totalChecks = totalPolicyChecks();
    if (totalChecks == 0) {
      return Optional.empty();
    }

    long open = openPolicyFindings();
    double score = (1.0 - ((double) open / totalChecks)) * 100.0;
    return Optional.of((int) Math.max(0, Math.round(score)));
  }

  public long openPolicyFindings() {
    long open = 0;
    for (Source source : Source.values()) {
      if (source.isPolicySource()) {
        open += findings.countBySourceAndStatus(source, FindingStatus.OPEN);
      }
    }
    return open;
  }

  /**
   * Denominator for the score: the last run per policy source that
   * actually completed. A scanner that crashed most recently keeps
   * contributing its last good measurement rather than dropping to zero
   * — a broken scanner should move the freshness strip, not the score.
   */
  public int totalPolicyChecks() {
    int total = 0;
    for (Source source : Source.values()) {
      if (!source.isPolicySource()) {
        continue;
      }
      total +=
          scanRuns
              .findFirstBySourceAndStatusOrderByRanAtDesc(source, RunStatus.COMPLETE)
              .map(ScanRun::totalChecks)
              .orElse(0);
    }
    return total;
  }

  public long openVulnerabilities() {
    return findings.countBySourceAndStatus(Source.TRIVY, FindingStatus.OPEN);
  }

  public List<Finding> openFindings() {
    return findings.findByStatusOrderByFirstSeenAtDesc(FindingStatus.OPEN);
  }

  public List<Remediation> remediationLog() {
    return remediations.findAllByOrderByRemediatedAtDesc();
  }

  /**
   * Average seconds from triggering event to fix.
   *
   * Records with a negative duration are excluded rather than averaged
   * in. A fix cannot land before the event that caused it, so a negative
   * value means bad input — clock skew between the event source and the
   * remediator, or a malformed timestamp. Averaging it in silently drags
   * the headline metric toward nonsense (observed: a single bad record
   * produced a reported MTTR of -8s).
   */
  public Optional<Long> averageMttrSeconds() {
    List<Long> valid =
        remediations.findAll().stream()
            .map(Remediation::mttrSeconds)
            .filter(seconds -> seconds >= 0)
            .toList();
    if (valid.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(valid.stream().mapToLong(Long::longValue).sum() / valid.size());
  }

  /** How many remediation records were rejected from the MTTR average as impossible. */
  public long invalidMttrRecords() {
    return remediations.findAll().stream().filter(r -> r.mttrSeconds() < 0).count();
  }

  /**
   * Per-source freshness. This is what makes a scanner that stopped
   * running visible — without it, a missing source looks identical to a
   * clean one.
   */
  public List<SourceFreshness> freshness() {
    List<SourceFreshness> out = new ArrayList<>();
    Instant now = Instant.now();
    for (Source source : Source.values()) {
      Optional<ScanRun> latest = scanRuns.findFirstBySourceOrderByRanAtDesc(source);
      if (latest.isEmpty()) {
        out.add(new SourceFreshness(source, null, null, false, true));
        continue;
      }
      ScanRun run = latest.get();
      boolean stale = run.ranAt != null && Duration.between(run.ranAt, now).compareTo(STALE_AFTER) > 0;
      out.add(new SourceFreshness(source, run.ranAt, run.status, stale, false));
    }
    return out;
  }

  public record SourceFreshness(
      Source source, Instant lastRanAt, RunStatus status, boolean stale, boolean neverRan) {}
}
