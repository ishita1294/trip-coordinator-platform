-- PREPARED ONLY. Deploy Lambda and API together after operator approval.
-- No legacy jobs or document/extraction/status data are backfilled.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE travel_documents
    ADD COLUMN processing_job_id uuid,
    ADD COLUMN processing_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN processing_delivery_count integer NOT NULL DEFAULT 0,
    ADD COLUMN processing_failure_count integer NOT NULL DEFAULT 0,
    ADD COLUMN processing_invalid_output_count integer NOT NULL DEFAULT 0,
    ADD COLUMN processing_last_error_code varchar(255),
    ADD COLUMN processing_failed_at timestamptz,
    ADD CONSTRAINT document_processing_counts_valid CHECK
      (processing_attempt_count >= 0 AND processing_delivery_count >= 0 AND processing_failure_count >= 0 AND processing_invalid_output_count >= 0
       AND processing_invalid_output_count <= processing_failure_count
       AND processing_failure_count <= processing_attempt_count),
    ADD CONSTRAINT document_processing_error_valid CHECK
      (processing_last_error_code IS NULL OR processing_last_error_code IN
       ('INVALID_EXTRACTION', 'INVALID_SOURCE', 'TECHNICAL_FAILURE', 'RETRIES_EXHAUSTED'));
ALTER TABLE document_processing_outbox
    ADD COLUMN processing_job_id uuid,
    ADD COLUMN processing_failed_at timestamptz;
CREATE UNIQUE INDEX document_processing_outbox_job_unique
    ON document_processing_outbox(processing_job_id) WHERE processing_job_id IS NOT NULL;
COMMIT;
