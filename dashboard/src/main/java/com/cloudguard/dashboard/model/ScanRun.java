package com.cloudguard.dashboard.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One execution of one scanner. Exists for two reasons beyond history:
 *
 * 1. It supplies the compliance score's denominator. SARIF and most
 *    scanner artifacts only carry failures, so "how many checks ran" has
 *    to be recorded explicitly or the percentage is uncomputable.
 * 2. It is the guard against a false all-clear. A scanner that crashed
 *    or produced nothing is recorded INCONCLUSIVE, and an INCONCLUSIVE
 *    run never resolves existing findings — otherwise "the scanner
 *    didn't run" would render identically to "everything is fine",
 *    which is the worst failure mode a compliance dashboard can have.
 */
@Entity
@Table(name = "scan_run")
public class ScanRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public Source source;

  public int passed;
  public int failed;

  /** GitHub Actions run id, or "local" for cluster/CLI-sourced scans. */
  public String runRef;

  public Instant ranAt;
  public Instant ingestedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public RunStatus status;

  protected ScanRun() {}

  public ScanRun(Source source, int passed, int failed, String runRef, Instant ranAt) {
    this.source = source;
    this.passed = passed;
    this.failed = failed;
    this.runRef = runRef;
    this.ranAt = ranAt;
    this.ingestedAt = Instant.now();
    this.status = (passed + failed > 0) ? RunStatus.COMPLETE : RunStatus.INCONCLUSIVE;
  }

  public int totalChecks() {
    return passed + failed;
  }
}
