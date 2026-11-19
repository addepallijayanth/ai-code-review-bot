package com.example.reviewbot.repository;

import com.example.reviewbot.model.UsageRecord;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    /** Spend of one installation since a point in time; {@code null} when there is none. */
    @Query("""
            select sum(u.costUsd) from UsageRecord u
            where u.createdAt >= :since
              and u.reviewId in (select r.id from Review r where r.installationId = :installationId)
            """)
    BigDecimal sumCostForInstallationSince(Long installationId, Instant since);
}
