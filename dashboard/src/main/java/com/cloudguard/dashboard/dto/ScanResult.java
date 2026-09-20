package com.cloudguard.dashboard.dto;

import com.cloudguard.dashboard.model.RunStatus;

/**
 * What an ingestion actually did. sweepSkipped is surfaced explicitly so
 * a caller can tell "nothing needed resolving" apart from "the sweep was
 * refused because the run reported no checks".
 */
public record ScanResult(
    RunStatus status, int findingsIngested, int findingsResolved, boolean sweepSkipped, String note) {}
