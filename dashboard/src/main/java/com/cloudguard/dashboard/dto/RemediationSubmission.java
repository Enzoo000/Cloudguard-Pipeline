package com.cloudguard.dashboard.dto;

import java.time.Instant;

public record RemediationSubmission(
    String sourceKey,
    String resource,
    String rule,
    Instant triggeredAt,
    Instant remediatedAt,
    String details) {}
