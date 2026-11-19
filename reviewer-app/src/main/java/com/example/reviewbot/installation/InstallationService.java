package com.example.reviewbot.installation;

import com.example.reviewbot.github.dto.GitHubUser;
import com.example.reviewbot.model.Installation;
import com.example.reviewbot.repository.InstallationRepository;
import com.example.reviewbot.webhook.events.InstallationEvent;
import com.example.reviewbot.webhook.events.InstallationRef;
import com.example.reviewbot.webhook.events.InstallationRepositoriesEvent;
import com.example.reviewbot.webhook.events.RepositoryName;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the {@code installation} table in sync with the App's installations. */
@Service
public class InstallationService {

    private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

    private final InstallationRepository installations;
    private final Clock clock;

    public InstallationService(InstallationRepository installations, Clock clock) {
        this.installations = installations;
        this.clock = clock;
    }

    /**
     * The row for an installation, created on first sight (for example when the App was installed
     * before this service started receiving webhooks).
     */
    public Installation ensure(long githubInstallationId, String accountLogin) {
        return installations.findByGithubInstallationId(githubInstallationId).orElseGet(() -> {
            try {
                return installations.saveAndFlush(new Installation(githubInstallationId,
                        accountLogin == null ? "unknown" : accountLogin, null, clock.instant()));
            } catch (DataIntegrityViolationException e) {
                return installations.findByGithubInstallationId(githubInstallationId).orElseThrow(() -> e);
            }
        });
    }

    @Transactional
    public void onInstallation(InstallationEvent event) {
        InstallationRef ref = event.installation();
        String login = login(ref.account());
        String type = ref.account() == null ? null : ref.account().type();
        Installation installation = installations.findByGithubInstallationId(ref.id())
                .orElseGet(() -> installations.save(new Installation(ref.id(), login, type, clock.instant())));
        switch (event.action()) {
            case "created" -> {
                installation.reinstall(login, type, clock.instant());
                installation.addRepositories(names(event.repositories()));
            }
            case "deleted" -> installation.uninstall(clock.instant());
            case "suspend" -> installation.suspend(clock.instant());
            case "unsuspend" -> installation.unsuspend();
            default -> log.debug("Installation event '{}' needs no action", event.action());
        }
        log.info("Installation {} ({}) {}", ref.id(), login, event.action());
    }

    @Transactional
    public void onRepositoriesChanged(InstallationRepositoriesEvent event) {
        InstallationRef ref = event.installation();
        Installation installation = installations.findByGithubInstallationId(ref.id())
                .orElseGet(() -> installations.save(new Installation(ref.id(), login(ref.account()),
                        ref.account() == null ? null : ref.account().type(), clock.instant())));
        installation.addRepositories(names(event.repositoriesAdded()));
        installation.removeRepositories(names(event.repositoriesRemoved()));
    }

    private static List<String> names(List<RepositoryName> repositories) {
        return repositories == null ? List.of() : repositories.stream().map(RepositoryName::fullName).toList();
    }

    private static String login(GitHubUser account) {
        return account == null || account.login() == null ? "unknown" : account.login();
    }
}
