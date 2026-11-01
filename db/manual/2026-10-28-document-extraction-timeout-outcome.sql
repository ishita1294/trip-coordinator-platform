-- PREPARED ONLY. Apply before the coordinated API/worker release; do not infer historical timeouts.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE travel_documents
    ADD COLUMN processing_extraction_timeout_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT document_extraction_timeouts_valid CHECK
      (processing_extraction_timeout_count >= 0
       AND processing_extraction_timeout_count <= processing_failure_count);
ALTER TABLE travel_documents DROP CONSTRAINT document_processing_error_valid;
ALTER TABLE travel_documents ADD CONSTRAINT document_processing_error_valid CHECK
    (processing_last_error_code IS NULL OR processing_last_error_code IN
     ('INVALID_EXTRACTION', 'INVALID_SOURCE', 'TECHNICAL_FAILURE', 'RETRIES_EXHAUSTED', 'EXTRACTION_TIMEOUT'));
COMMIT;
