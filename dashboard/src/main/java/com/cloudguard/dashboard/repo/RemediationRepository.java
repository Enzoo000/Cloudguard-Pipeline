package com.cloudguard.dashboard.repo;

import com.cloudguard.dashboard.model.Remediation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemediationRepository extends JpaRepository<Remediation, Long> {

  boolean existsBySourceKey(String sourceKey);

  List<Remediation> findAllByOrderByRemediatedAtDesc();
}
