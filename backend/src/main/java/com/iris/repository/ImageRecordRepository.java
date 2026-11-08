package com.iris.repository;

import com.iris.model.ImageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImageRecordRepository extends JpaRepository<ImageRecord, Long> {
    List<ImageRecord> findAllByOrderByCreatedAtDesc();

    Optional<ImageRecord> findFirstByOriginalChecksum(String checksum);

    Optional<ImageRecord> findByToken(String token);
}
