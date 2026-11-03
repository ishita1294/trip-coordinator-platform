-- RUNTIME INACTIVE. Durable integration is guarded; DO NOT APPLY without separate approval.
-- Additive bookkeeping only: no trip/reservation changes, provider content, credentials,
-- seeded spending limits, or automatic startup execution.
-- Future execution: psql with ON_ERROR_STOP=1 against the explicitly verified target.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

-- One lockable pointer per stable billing/project scope, independent of versions.
-- NULL active_revision_id means no paid admission. No policies are seeded here.
CREATE TABLE explore_provider_usage_policies (
    budget_scope varchar(96) PRIMARY KEY,
    active_revision_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_explore_usage_policy_scope CHECK (length(budget_scope) > 0)
);

-- Append-only approvals; published price books are external immutable manifests.
-- window_changes contains accounting-only before/after limit/hold values and counter
-- snapshots for affected window IDs, never provider payloads or location data.
CREATE TABLE explore_provider_usage_policy_revisions (
    id uuid PRIMARY KEY,
    budget_scope varchar(96) NOT NULL REFERENCES explore_provider_usage_policies(budget_scope),
    previous_revision_id uuid,
    protocol_version integer NOT NULL,
    price_book_version varchar(64) NOT NULL,
    price_book_digest char(64) NOT NULL,
    hourly_limit_micros bigint NOT NULL,
    daily_limit_micros bigint NOT NULL,
    monthly_limit_micros bigint NOT NULL,
    admissions_enabled boolean NOT NULL,
    effective_at timestamptz NOT NULL,
    admission_valid_until timestamptz NOT NULL,
    approved_by varchar(160) NOT NULL,
    approval_reference varchar(160) NOT NULL,
    reason varchar(500) NOT NULL,
    window_changes jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_explore_usage_revision_scope UNIQUE (budget_scope, id),
    CONSTRAINT fk_explore_usage_previous_revision FOREIGN KEY (budget_scope, previous_revision_id)
        REFERENCES explore_provider_usage_policy_revisions(budget_scope, id),
    CONSTRAINT ck_explore_usage_revision_configuration CHECK (
        protocol_version > 0 AND length(price_book_version) > 0
        AND price_book_digest ~ '^[0-9a-f]{64}$'
        AND hourly_limit_micros > 0 AND daily_limit_micros > 0 AND monthly_limit_micros > 0),
    CONSTRAINT ck_explore_usage_revision_interval CHECK (admission_valid_until > effective_at),
    CONSTRAINT ck_explore_usage_revision_approval CHECK (
        length(approved_by) > 0 AND length(approval_reference) > 0 AND length(reason) > 0),
    CONSTRAINT ck_explore_usage_revision_window_changes CHECK (jsonb_typeof(window_changes) = 'array')
);
ALTER TABLE explore_provider_usage_policies
    ADD CONSTRAINT fk_explore_usage_active_revision FOREIGN KEY (budget_scope, active_revision_id)
        REFERENCES explore_provider_usage_policy_revisions(budget_scope, id);

-- One shared row per global UTC hour/day/month; revision is deliberately NOT part
-- of the unique key. A new policy must reuse the same counters and window identity.
CREATE TABLE explore_provider_usage_windows (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_key varchar(64) NOT NULL,
    budget_scope varchar(96) NOT NULL REFERENCES explore_provider_usage_policies(budget_scope),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    currency char(3) NOT NULL DEFAULT 'USD',
    applied_policy_revision_id uuid NOT NULL,
    limit_micros bigint NOT NULL,
    reserved_micros bigint NOT NULL DEFAULT 0,
    charged_micros bigint NOT NULL DEFAULT 0,
    uncertainty_hold_micros bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_explore_usage_window_revision FOREIGN KEY (budget_scope, applied_policy_revision_id)
        REFERENCES explore_provider_usage_policy_revisions(budget_scope, id),
    CONSTRAINT uq_explore_usage_window UNIQUE (policy_key, budget_scope, starts_at),
    CONSTRAINT ck_explore_usage_window_interval CHECK (ends_at > starts_at),
    CONSTRAINT ck_explore_usage_window_policy CHECK (policy_key IN ('GOOGLE_UTC_HOUR', 'GOOGLE_UTC_DAY', 'GOOGLE_UTC_MONTH')),
    CONSTRAINT ck_explore_usage_window_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_explore_usage_window_configuration CHECK (
        length(budget_scope) > 0),
    CONSTRAINT ck_explore_usage_window_amounts CHECK (
        limit_micros > 0 AND reserved_micros >= 0 AND charged_micros >= 0
        AND uncertainty_hold_micros >= 0)
);

-- A server-generated idempotent phase (discovery, candidate batch, route correction).
-- IDs are bookkeeping/correlation, NOT authentication or public authorization tokens.
CREATE TABLE explore_provider_usage_reservations (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    phase_key varchar(96) NOT NULL,
    policy_revision_id uuid NOT NULL REFERENCES explore_provider_usage_policy_revisions(id),
    session_id uuid,
    operation_plan_digest char(64) NOT NULL,
    hourly_window_id bigint NOT NULL REFERENCES explore_provider_usage_windows(id),
    daily_window_id bigint NOT NULL REFERENCES explore_provider_usage_windows(id),
    monthly_window_id bigint NOT NULL REFERENCES explore_provider_usage_windows(id),
    state varchar(16) NOT NULL DEFAULT 'OPEN',
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at timestamptz,
    CONSTRAINT uq_explore_usage_phase UNIQUE (request_id, phase_key),
    CONSTRAINT ck_explore_usage_phase_key CHECK (length(phase_key) > 0),
    CONSTRAINT ck_explore_usage_plan_digest CHECK (operation_plan_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_explore_usage_distinct_windows CHECK (hourly_window_id <> daily_window_id
        AND hourly_window_id <> monthly_window_id AND daily_window_id <> monthly_window_id),
    CONSTRAINT ck_explore_usage_reservation_interval CHECK (expires_at > created_at),
    CONSTRAINT ck_explore_usage_reservation_state CHECK (
        (state = 'OPEN' AND settled_at IS NULL)
        OR (state IN ('CLOSED', 'ABANDONED') AND settled_at IS NOT NULL))
);

-- Preallocated logical attempts; each is authorized at most once before network I/O.
-- No request payload, response payload, route, coordinates, place name/types or key.
-- Rates are conservative planning snapshots, not claims about actual Cloud billing.
CREATE TABLE explore_provider_usage_operations (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES explore_provider_usage_reservations(id),
    operation_key varchar(96) NOT NULL,
    operation_kind varchar(32) NOT NULL,
    sku varchar(96) NOT NULL,
    price_book_version varchar(64) NOT NULL,
    billable_units integer NOT NULL,
    unit_price_micros bigint NOT NULL,
    estimated_cost_micros bigint GENERATED ALWAYS AS (billable_units::bigint * unit_price_micros) STORED,
    state varchar(24) NOT NULL DEFAULT 'RESERVED',
    dispatch_fence uuid,
    authorized_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_explore_usage_operation UNIQUE (reservation_id, operation_key),
    CONSTRAINT ck_explore_usage_operation_identity CHECK (
        length(operation_key) > 0 AND length(sku) > 0 AND length(price_book_version) > 0),
    CONSTRAINT ck_explore_usage_operation_kind CHECK (operation_kind IN (
        'PLACES_NEARBY', 'PLACES_TEXT', 'PLACES_DETAILS', 'ROUTES_COMPUTE', 'ROUTES_MATRIX')),
    CONSTRAINT ck_explore_usage_operation_cost CHECK (billable_units > 0 AND unit_price_micros >= 0),
    CONSTRAINT ck_explore_usage_operation_state CHECK (
        (state = 'RESERVED' AND dispatch_fence IS NULL AND authorized_at IS NULL AND completed_at IS NULL)
        OR (state = 'RELEASED' AND dispatch_fence IS NULL AND authorized_at IS NULL AND completed_at IS NOT NULL)
        OR (state = 'DISPATCH_AUTHORIZED' AND dispatch_fence IS NOT NULL AND authorized_at IS NOT NULL AND completed_at IS NULL)
        OR (state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN') AND dispatch_fence IS NOT NULL AND authorized_at IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_explore_usage_operation_times CHECK (
        (authorized_at IS NULL OR authorized_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= COALESCE(authorized_at, created_at)))
);

-- Protect audit revisions and operation pricing snapshots; no accounting runtime
-- is installed. Future admission/update transactions still require explicit code.
CREATE FUNCTION protect_explore_usage_policy_revision() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Provider usage policy revisions are append-only';
END;
$$;
CREATE TRIGGER explore_usage_policy_revision_immutable
BEFORE UPDATE OR DELETE ON explore_provider_usage_policy_revisions
FOR EACH ROW EXECUTE FUNCTION protect_explore_usage_policy_revision();

CREATE FUNCTION protect_explore_usage_operation_snapshot() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.reservation_id, NEW.operation_key, NEW.operation_kind,
           NEW.sku, NEW.price_book_version, NEW.billable_units, NEW.unit_price_micros, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.reservation_id, OLD.operation_key, OLD.operation_kind,
           OLD.sku, OLD.price_book_version, OLD.billable_units, OLD.unit_price_micros, OLD.created_at) THEN
        RAISE EXCEPTION 'Provider usage operation rate snapshots are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER explore_usage_operation_snapshot_immutable
BEFORE UPDATE ON explore_provider_usage_operations
FOR EACH ROW EXECUTE FUNCTION protect_explore_usage_operation_snapshot();

CREATE INDEX ix_explore_usage_open_expiry
    ON explore_provider_usage_reservations(expires_at) WHERE state = 'OPEN';
CREATE INDEX ix_explore_usage_session
    ON explore_provider_usage_reservations(session_id, created_at) WHERE session_id IS NOT NULL;
CREATE INDEX ix_explore_usage_unfinished_operations
    ON explore_provider_usage_operations(reservation_id, state)
    WHERE state IN ('RESERVED', 'DISPATCH_AUTHORIZED');

COMMENT ON TABLE explore_provider_usage_windows IS
    'Stable global windows across policy revisions. Over-limit rows are valid after cap reduction; admission must fail closed.';
COMMENT ON TABLE explore_provider_usage_reservations IS
    'Phase-level reservation; atomic runtime protocol in design document is required before activation.';
COMMENT ON TABLE explore_provider_usage_operations IS
    'Attempt-level estimated SKU charges. Authorized/failed/unknown attempts are never automatically refunded.';

COMMIT;
