package com.cloudguard.dashboard.dto;

import com.cloudguard.dashboard.model.Source;

import java.time.Instant;
import java.util.List;

/**
 * A whole scan run, submitted atomically.
 *
 * Deliberately not per-finding: the resolution sweep needs the complete
 * set of findings a scanner reported in one run to know what has
 * disappeared since last time. Accepting findings one at a time would
 * make it impossible to tell "this flaw is fixed" from "this POST
 * hasn't arrived yet".
 */
public record ScanSubmission(
    Source source,
    int passed,
    int failed,
    String runRef,
    Instant ranAt,
    List<FindingSubmission> findings) {

  public List<FindingSubmission> findingsOrEmpty() {
    return findings == null ? List.of() : findings;
  }
}
