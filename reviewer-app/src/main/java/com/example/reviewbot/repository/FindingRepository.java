package com.example.reviewbot.repository;

import com.example.reviewbot.model.Finding;
import com.example.reviewbot.model.ReviewStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    List<Finding> findByReviewIdOrderByOrdinal(Long reviewId);

    Optional<Finding> findByReviewIdAndOrdinal(Long reviewId, int ordinal);

    @Modifying
    @Query("delete from Finding f where f.reviewId = :reviewId")
    int deleteByReviewId(Long reviewId);

    @Modifying
    @Query("update Finding f set f.postedInline = false where f.reviewId = :reviewId")
    int markNotInline(Long reviewId);

    @Transactional
    @Modifying
    @Query("update Finding f set f.accepted = true where f.id in :ids")
    int markAccepted(Collection<Long> ids);

    /** Fingerprints of everything already posted on this pull request, used to avoid repeating a finding. */
    @Query("""
            select f.fingerprint from Finding f
            where f.reviewId in (
                select r.id from Review r
                where r.repo = :repo and r.prNumber = :prNumber and r.status = :status)
            """)
    Set<String> findFingerprints(String repo, int prNumber, ReviewStatus status);

    /** Inline suggestions on this pull request that the author has not applied yet. */
    @Query("""
            select f from Finding f
            where f.suggestion is not null and f.postedInline = true and f.accepted = false
              and f.reviewId in (
                select r.id from Review r
                where r.repo = :repo and r.prNumber = :prNumber and r.status = :status)
            """)
    List<Finding> findPendingSuggestions(String repo, int prNumber, ReviewStatus status);
}
