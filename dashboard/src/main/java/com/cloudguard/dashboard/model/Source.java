package com.cloudguard.dashboard.model;

/**
 * Where a finding came from.
 *
 * CHECKOV, OPA and KYVERNO are policy scanners: they report explicit
 * pass and fail counts, so they can carry a compliance percentage.
 * TRIVY is a vulnerability scanner — it reports CVEs found, with no
 * meaningful "checks passed" count, so it is deliberately excluded from
 * the compliance score and reported as a separate vulnerability count.
 * See isPolicySource().
 */
public enum Source {
  CHECKOV,
  TRIVY,
  OPA,
  KYVERNO;

  public boolean isPolicySource() {
    return this != TRIVY;
  }
}
