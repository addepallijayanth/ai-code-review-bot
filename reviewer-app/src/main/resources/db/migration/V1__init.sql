-- Installations of the GitHub App and the repositories each one can access.
CREATE TABLE installation (
    id                     BIGSERIAL PRIMARY KEY,
    github_installation_id BIGINT       NOT NULL UNIQUE,
    account_login          VARCHAR(255) NOT NULL,
    account_type           VARCHAR(32),
    installed_at           TIMESTAMPTZ  NOT NULL,
    suspended_at           TIMESTAMPTZ,
    uninstalled_at         TIMESTAMPTZ
);

CREATE TABLE installation_repository (
    installation_id BIGINT       NOT NULL REFERENCES installation (id) ON DELETE CASCADE,
    full_name       VARCHAR(255) NOT NULL,
    PRIMARY KEY (installation_id, full_name)
);

-- One row per reviewed commit. The unique key is what makes redeliveries and retries idempotent.
CREATE TABLE review (
    id               BIGSERIAL PRIMARY KEY,
    installation_id  BIGINT       NOT NULL REFERENCES installation (id),
    repo             VARCHAR(255) NOT NULL,
    pr_number        INT          NOT NULL,
    commit_sha       VARCHAR(64)  NOT NULL,
    delivery_id      VARCHAR(64),
    status           VARCHAR(16)  NOT NULL,
    status_detail    TEXT,
    files_reviewed   INT          NOT NULL DEFAULT 0,
    files_skipped    INT          NOT NULL DEFAULT 0,
    github_review_id BIGINT,
    attempts         INT          NOT NULL DEFAULT 1,
    latency_ms       BIGINT,
    received_at      TIMESTAMPTZ  NOT NULL,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_review_commit UNIQUE (repo, pr_number, commit_sha),
    CONSTRAINT ck_review_status CHECK (status IN ('RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED'))
);

CREATE INDEX idx_review_received_at ON review (received_at DESC);
CREATE INDEX idx_review_installation ON review (installation_id, received_at DESC);

CREATE TABLE finding (
    id            BIGSERIAL PRIMARY KEY,
    review_id     BIGINT        NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    ordinal       INT           NOT NULL,
    file          VARCHAR(1024) NOT NULL,
    line          INT           NOT NULL,
    start_line    INT,
    severity      VARCHAR(16)   NOT NULL,
    category      VARCHAR(32)   NOT NULL,
    title         VARCHAR(255)  NOT NULL,
    message       TEXT          NOT NULL,
    suggestion    TEXT,
    fingerprint   VARCHAR(64)   NOT NULL,
    posted_inline BOOLEAN       NOT NULL,
    accepted      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_finding_ordinal UNIQUE (review_id, ordinal)
);

CREATE INDEX idx_finding_fingerprint ON finding (fingerprint);

-- Token usage per review, aggregated per model and purpose (triage / review / explain / fix).
CREATE TABLE usage_record (
    id                 BIGSERIAL PRIMARY KEY,
    review_id          BIGINT         NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    model              VARCHAR(64)    NOT NULL,
    purpose            VARCHAR(16)    NOT NULL,
    calls              INT            NOT NULL,
    tokens_in          BIGINT         NOT NULL,
    tokens_out         BIGINT         NOT NULL,
    cache_read_tokens  BIGINT         NOT NULL,
    cache_write_tokens BIGINT         NOT NULL,
    cost_usd           NUMERIC(12, 6) NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_usage_review ON usage_record (review_id);
CREATE INDEX idx_usage_created_at ON usage_record (created_at);
