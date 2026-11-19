package com.example.reviewbot.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** One installation of the GitHub App on a user or organization account. */
@Entity
@Table(name = "installation")
public class Installation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_installation_id", nullable = false, unique = true)
    private long githubInstallationId;

    @Column(name = "account_login", nullable = false)
    private String accountLogin;

    @Column(name = "account_type", length = 32)
    private String accountType;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "uninstalled_at")
    private Instant uninstalledAt;

    @ElementCollection
    @CollectionTable(name = "installation_repository", joinColumns = @JoinColumn(name = "installation_id"))
    @Column(name = "full_name", nullable = false)
    private Set<String> repositories = new HashSet<>();

    protected Installation() {
    }

    public Installation(long githubInstallationId, String accountLogin, String accountType, Instant installedAt) {
        this.githubInstallationId = githubInstallationId;
        this.accountLogin = accountLogin;
        this.accountType = accountType;
        this.installedAt = installedAt;
    }

    public void reinstall(String accountLogin, String accountType, Instant at) {
        this.accountLogin = accountLogin;
        this.accountType = accountType;
        this.installedAt = at;
        this.uninstalledAt = null;
        this.suspendedAt = null;
    }

    public void uninstall(Instant at) {
        this.uninstalledAt = at;
        this.repositories.clear();
    }

    public void suspend(Instant at) {
        this.suspendedAt = at;
    }

    public void unsuspend() {
        this.suspendedAt = null;
    }

    public void addRepositories(Collection<String> fullNames) {
        repositories.addAll(fullNames);
    }

    public void removeRepositories(Collection<String> fullNames) {
        repositories.removeAll(fullNames);
    }

    public Long getId() {
        return id;
    }

    public long getGithubInstallationId() {
        return githubInstallationId;
    }

    public String getAccountLogin() {
        return accountLogin;
    }

    public String getAccountType() {
        return accountType;
    }

    public Instant getInstalledAt() {
        return installedAt;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public Instant getUninstalledAt() {
        return uninstalledAt;
    }

    public Set<String> getRepositories() {
        return repositories;
    }

    public boolean isActive() {
        return uninstalledAt == null && suspendedAt == null;
    }
}
