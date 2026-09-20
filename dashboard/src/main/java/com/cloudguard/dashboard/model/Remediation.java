package com.cloudguard.dashboard.model;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;

/**
 * One automated fix performed by the remediation Lambda.
 *
 * triggeredAt is when the event that caused the fix was emitted, not
 * when a human noticed anything — the difference between it and
 * remediatedAt is the mean-time-to-remediate the dashboard reports.
 *
 * sourceKey is the S3 object the record was read from. It is unique so
 * that re-running the sync script cannot double-count the same fix.
 */
@Entity
@Table(
    name = "remediation",
    indexes = @Index(name = "idx_remediation_source_key", columnList = "sourceKey", unique = true))
public class Remediation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true)
  public String sourceKey;

  public String resource;
  public String rule;

  public Instant triggeredAt;
  public Instant remediatedAt;

  @Column(length = 2000)
  public String details;

  public Instant ingestedAt;

  protected Remediation() {}

  public Remediation(
      String sourceKey,
      String resource,
      String rule,
      Instant triggeredAt,
      Instant remediatedAt,
      String details) {
    this.sourceKey = sourceKey;
    this.resource = resource;
    this.rule = rule;
    this.triggeredAt = triggeredAt;
    this.remediatedAt = remediatedAt;
    this.details = details;
    this.ingestedAt = Instant.now();
  }

  /** Seconds between the triggering event and the fix landing. */
  public long mttrSeconds() {
    if (triggeredAt == null || remediatedAt == null) {
      return 0;
    }
    return Duration.between(triggeredAt, remediatedAt).toSeconds();
  }
}
