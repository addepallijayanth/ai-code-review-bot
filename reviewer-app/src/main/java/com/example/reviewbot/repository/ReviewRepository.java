package com.example.reviewbot.repository;

import com.example.reviewbot.model.Review;
import com.example.reviewbot.model.ReviewStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByRepoAndPrNumberAndCommitSha(String repo, int prNumber, String commitSha);

    Optional<Review> findFirstByRepoAndPrNumberAndStatusOrderByCompletedAtDesc(String repo, int prNumber,
                                                                              ReviewStatus status);

    boolean existsByRepoAndPrNumberAndStatusAndStatusDetailAndCompletedAtAfter(String repo, int prNumber,
                                                                              ReviewStatus status,
                                                                              String statusDetail,
                                                                              Instant completedAfter);
}
