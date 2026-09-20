package com.cloudguard.dashboard.dto;

public record FindingSubmission(String resource, String ruleId, String message, String severity) {}
