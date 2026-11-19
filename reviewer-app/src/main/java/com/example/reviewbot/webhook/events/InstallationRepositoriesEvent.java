package com.example.reviewbot.webhook.events;

import java.util.List;

/** {@code installation_repositories} webhook: repositories added to or removed from an installation. */
public record InstallationRepositoriesEvent(
        String action,
        InstallationRef installation,
        List<RepositoryName> repositoriesAdded,
        List<RepositoryName> repositoriesRemoved) {
}
