package com.cloudguard.dashboard.model;

import jakarta.persistence.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Current state of one unique issue, not one row per sighting.
 *
 * CI re-runs the same scanners on every commit, so the same flaw is
 * reported over and over. Keying on a fingerprint means a recurring
 * finding updates in place instead of inserting duplicates that would
 * silently deflate the compliance score.
 */
@Entity
@Table(
    name = "finding",
    indexes = @Index(name = "idx_finding_fingerprint", columnList = "fingerprint", unique = true))
public class Finding {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  /** SHA-256 of source + resource + ruleId. Unique per distinct issue. */
  @Column(nullable = false, unique = true, length = 64)
  public String fingerprint;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public Source source;

  public String resource;
  public String ruleId;

  @Column(length = 2000)
  public String message;

  public String severity;

  public Instant firstSeenAt;
  public Instant lastSeenAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public FindingStatus status;

  public Instant resolvedAt;

  protected Finding() {}

  public Finding(Source source, String resource, String ruleId, String message, String severity) {
    this.fingerprint = fingerprint(source, resource, ruleId);
    this.source = source;
    this.resource = resource;
    this.ruleId = ruleId;
    this.message = message;
    this.severity = severity;
    this.firstSeenAt = Instant.now();
    this.lastSeenAt = this.firstSeenAt;
    this.status = FindingStatus.OPEN;
  }

  public static String fingerprint(Source source, String resource, String ruleId) {
    String raw = source + "|" + resource + "|" + ruleId;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public void markSeen(String message, String severity) {
    this.lastSeenAt = Instant.now();
    this.message = message;
    this.severity = severity;
    // A finding that reappears after being resolved is open again.
    this.status = FindingStatus.OPEN;
    this.resolvedAt = null;
  }

  public void resolve() {
    this.status = FindingStatus.RESOLVED;
    this.resolvedAt = Instant.now();
  }
}
