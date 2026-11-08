package com.iris.repository;

import com.iris.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop50ByOrderByTimestampDesc();

    List<AuditEvent> findByRecordIdOrderByTimestampAsc(Long recordId);
}
