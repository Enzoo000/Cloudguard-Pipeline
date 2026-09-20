package com.cloudguard.dashboard.model;

public enum RunStatus {
  /** The scanner ran and reported at least one check. */
  COMPLETE,
  /** The scanner reported no checks at all — crashed, empty, or failed. */
  INCONCLUSIVE
}
