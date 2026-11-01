-- Narrow additive job opt-in only. No receipt/native-confirmation activation, backfill or row rewrite.
-- Apply only after reviewed compatibility/drain/backup checks; old workers must not receive native jobs.
BEGIN;
SET LOCAL lock_timeout='3s';
SET LOCAL statement_timeout='30s';
ALTER TABLE document_processing_outbox DROP CONSTRAINT document_processing_extraction_contract_valid;
ALTER TABLE document_processing_outbox ADD CONSTRAINT document_processing_extraction_contract_valid CHECK (
 extraction_contract IS NULL OR extraction_contract IN
 ('LEGACY','SEMANTIC_V2','SEMANTIC_V3_4','AUTO_V3_4','FLIGHT_V1','UNSUPPORTED','SOURCE_BOOKING_V1_REVIEW'));
-- Preserve prevent_document_processing_contract_change() byte-for-byte: native choices cannot be changed.
COMMIT;
-- Keep this compatible superset on rollback. Do not route native jobs to old workers or native data
-- to old readers. Disable only the opt-in and retain native-aware readers/consumers until accounted for.
