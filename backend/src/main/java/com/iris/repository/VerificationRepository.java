package com.iris.repository;

import com.iris.model.Verification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VerificationRepository extends JpaRepository<Verification, Long> {
    List<Verification> findByRecordIdOrderByCreatedAtDesc(Long recordId);
}
