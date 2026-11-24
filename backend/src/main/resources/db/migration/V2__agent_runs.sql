-- Agent runs and their steps: everything the loop does is stored in full (FR7, NFR10), not only totals.
--
-- workspace_id and triggered_by will reference workspace and app_user once the tenant tables exist (stage 1.5);
-- that migration adds the foreign keys. Until then they are plain columns, but workspace_id is already NOT NULL,
-- so no run can exist outside a workspace.

CREATE TABLE agent_run (
    id                uuid PRIMARY KEY,
    workspace_id      uuid         NOT NULL,
    github_repo_id    bigint,
    triggered_by      uuid,
    agent             varchar(100) NOT NULL,
    trigger_source    varchar(50)  NOT NULL,
    input             jsonb,
    status            varchar(20)  NOT NULL,
    failure_reason    varchar(40),
    failure_detail    text,
    started_at        timestamptz  NOT NULL,
    finished_at       timestamptz,
    steps             integer      NOT NULL DEFAULT 0,
    input_tokens      integer      NOT NULL DEFAULT 0,
    output_tokens     integer      NOT NULL DEFAULT 0,
    cache_read_tokens integer      NOT NULL DEFAULT 0,
    repeated_calls    integer      NOT NULL DEFAULT 0,
    model             varchar(100) NOT NULL,
    final_output      jsonb,
    CONSTRAINT agent_run_status_valid CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT agent_run_failure_has_reason CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

CREATE INDEX agent_run_workspace_started_idx ON agent_run (workspace_id, started_at DESC);

CREATE TABLE agent_step (
    id            uuid PRIMARY KEY,
    run_id        uuid        NOT NULL REFERENCES agent_run (id),
    seq           integer     NOT NULL,
    type          varchar(10) NOT NULL,
    tool_name     varchar(64),
    args          jsonb,
    output        jsonb,
    is_error      boolean     NOT NULL DEFAULT false,
    input_tokens  integer,
    output_tokens integer,
    duration_ms   integer     NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT agent_step_type_valid CHECK (type IN ('LLM', 'TOOL')),
    CONSTRAINT agent_step_seq_unique UNIQUE (run_id, seq)
);
