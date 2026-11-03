-- ADDITIVE CORRECTION; approval required before AWS-DEV execution. No policy activation.
BEGIN;
SET LOCAL lock_timeout='3s';
SET LOCAL statement_timeout='30s';
ALTER TABLE explore_provider_usage_policy_revisions ADD COLUMN accounting_mode varchar(24) NOT NULL DEFAULT 'OPTIONAL_HARD_CAP';
ALTER TABLE explore_provider_usage_policy_revisions DROP CONSTRAINT ck_explore_usage_revision_configuration;
ALTER TABLE explore_provider_usage_policy_revisions ADD CONSTRAINT ck_explore_usage_revision_configuration CHECK (
 protocol_version>0 AND length(price_book_version)>0 AND price_book_digest ~ '^[0-9a-f]{64}$'
 AND ((accounting_mode='TRACK_ONLY' AND hourly_limit_micros=0 AND daily_limit_micros=0 AND monthly_limit_micros=0)
 OR (accounting_mode='OPTIONAL_HARD_CAP' AND hourly_limit_micros>0 AND daily_limit_micros>0 AND monthly_limit_micros>0)));
ALTER TABLE explore_provider_usage_windows DROP CONSTRAINT ck_explore_usage_window_amounts;
ALTER TABLE explore_provider_usage_windows ADD CONSTRAINT ck_explore_usage_window_amounts CHECK (
 limit_micros>=0 AND reserved_micros>=0 AND charged_micros>=0 AND uncertainty_hold_micros>=0);
COMMENT ON COLUMN explore_provider_usage_policy_revisions.accounting_mode IS 'Explicit operator-approved accounting policy. TRACK_ONLY uses zero cap markers, not a financial allowance. admissions_enabled remains the persistent emergency stop.';
COMMIT;
