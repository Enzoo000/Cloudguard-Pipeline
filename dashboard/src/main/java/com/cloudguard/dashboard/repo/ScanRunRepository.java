package com.cloudguard.dashboard.repo;

import com.cloudguard.dashboard.model.RunStatus;
import com.cloudguard.dashboard.model.ScanRun;
import com.cloudguard.dashboard.model.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScanRunRepository extends JpaRepository<ScanRun, Long> {

  /** Latest run per source regardless of status — drives the freshness strip. */
  Optional<ScanRun> findFirstBySourceOrderByRanAtDesc(Source source);

  /**
   * Latest *conclusive* run per source — drives the score denominator.
   *
   * Deliberately not the same query as above: using the latest run of any
   * status meant a crashed scanner dropped its checks out of the
   * denominator and moved the score (observed: 94% to 63%) even though
   * nothing about the real posture had changed. The last run that
   * actually completed is the most recent trustworthy measurement.
   */
  Optional<ScanRun> findFirstBySourceAndStatusOrderByRanAtDesc(Source source, RunStatus status);

  List<ScanRun> findAllByOrderByRanAtDesc();
}
