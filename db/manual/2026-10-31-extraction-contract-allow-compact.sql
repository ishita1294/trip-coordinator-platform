-- Additive correction. 2026-10-30-source-booking-read-only-routing.sql recreated this constraint without the
-- compact values added by 2026-10-28-document-processing-compact-contract.sql. Restore them; keep the native value.
-- No rows are updated and prevent_document_processing_contract_change() is not touched.
-- Apply only after reviewed compatibility checks; rollback is not needed, the allowlist is a superset.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE document_processing_outbox
    DROP CONSTRAINT document_processing_extraction_contract_valid,
    ADD CONSTRAINT document_processing_extraction_contract_valid CHECK (
        extraction_contract IS NULL OR extraction_contract IN
            ('LEGACY', 'SEMANTIC_V2', 'SEMANTIC_V3_4', 'AUTO_V3_4',
             'SEMANTIC_V3_4_COMPACT', 'AUTO_V3_4_COMPACT', 'FLIGHT_V1', 'UNSUPPORTED',
             'SOURCE_BOOKING_V1_REVIEW')) NOT VALID;
ALTER TABLE document_processing_outbox VALIDATE CONSTRAINT document_processing_extraction_contract_valid;
COMMIT;
