CREATE TABLE IF NOT EXISTS policies (
    tenant_id       VARCHAR(100) NOT NULL,
    version         INTEGER NOT NULL,
    policy_json     TEXT NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS one_active_policy_per_tenant
    ON policies (tenant_id) WHERE active = TRUE;

CREATE TABLE IF NOT EXISTS evaluations (
    tenant_id       VARCHAR(100) NOT NULL,
    id               UUID NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    event_id         VARCHAR(200) NOT NULL,
    request_json     TEXT NOT NULL,
    decision_json    TEXT NOT NULL,
    decision         VARCHAR(20) NOT NULL,
    score            INTEGER NOT NULL,
    evaluated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT evaluations_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS evaluations_incident_lookup
    ON evaluations (tenant_id, decision, evaluated_at DESC);
