package com.cloudguard.dashboard.repo;

import com.cloudguard.dashboard.model.Finding;
import com.cloudguard.dashboard.model.FindingStatus;
import com.cloudguard.dashboard.model.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FindingRepository extends JpaRepository<Finding, Long> {

  Optional<Finding> findByFingerprint(String fingerprint);

  List<Finding> findByStatusOrderByFirstSeenAtDesc(FindingStatus status);

  /** Used by the resolution sweep, which is always scoped to one source. */
  List<Finding> findBySourceAndStatus(Source source, FindingStatus status);

  long countByStatus(FindingStatus status);

  long countBySourceAndStatus(Source source, FindingStatus status);
}
