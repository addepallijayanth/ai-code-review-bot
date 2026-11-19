package com.example.reviewbot.repository;

import com.example.reviewbot.model.Installation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallationRepository extends JpaRepository<Installation, Long> {

    Optional<Installation> findByGithubInstallationId(long githubInstallationId);
}
