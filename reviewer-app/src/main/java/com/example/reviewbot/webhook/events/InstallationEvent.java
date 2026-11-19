package com.example.reviewbot.webhook.events;

import java.util.List;

/** {@code installation} webhook: created, deleted, suspend, unsuspend, new_permissions_accepted. */
public record InstallationEvent(String action, InstallationRef installation, List<RepositoryName> repositories) {
}
