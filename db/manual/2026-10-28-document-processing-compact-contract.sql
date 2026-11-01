-- PREPARED ONLY. Requires the existing extraction-contract column, constraint and trigger.
-- Deploy compatible readers/consumers before configuring new producers for AUTO_V3_4_COMPACT.
-- No rows, stored extractions or resolved decisions are updated or backfilled.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE document_processing_outbox
    DROP CONSTRAINT document_processing_extraction_contract_valid,
    ADD CONSTRAINT document_processing_extraction_contract_valid CHECK (
        extraction_contract IS NULL OR extraction_contract IN
            ('LEGACY', 'SEMANTIC_V2', 'SEMANTIC_V3_4', 'AUTO_V3_4',
             'SEMANTIC_V3_4_COMPACT', 'AUTO_V3_4_COMPACT', 'FLIGHT_V1', 'UNSUPPORTED')) NOT VALID;
ALTER TABLE document_processing_outbox VALIDATE CONSTRAINT document_processing_extraction_contract_valid;

CREATE OR REPLACE FUNCTION prevent_document_processing_contract_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.extraction_contract IS DISTINCT FROM OLD.extraction_contract THEN
        -- Only pending automatic jobs may resolve, exactly once. NULL is never pending.
        IF OLD.extraction_contract IS NULL
           OR OLD.extraction_contract NOT IN ('AUTO_V3_4', 'AUTO_V3_4_COMPACT')
           OR NEW.extraction_contract IS NULL
           OR NOT ((OLD.extraction_contract = 'AUTO_V3_4' AND NEW.extraction_contract IN ('SEMANTIC_V3_4', 'FLIGHT_V1', 'UNSUPPORTED'))
               OR (OLD.extraction_contract = 'AUTO_V3_4_COMPACT' AND NEW.extraction_contract IN ('SEMANTIC_V3_4_COMPACT', 'FLIGHT_V1', 'UNSUPPORTED')))
           OR NEW.processing_job_id IS DISTINCT FROM OLD.processing_job_id
           OR NEW.document_id IS DISTINCT FROM OLD.document_id THEN
            RAISE EXCEPTION 'Resolved or historical processing contract is immutable';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM travel_documents document
            WHERE document.id = NEW.document_id AND document.processing_job_id = NEW.processing_job_id
              AND document.processing_status = 'PROCESSING' AND document.processing_attempt_id IS NOT NULL
              AND ((NEW.extraction_contract IN ('SEMANTIC_V3_4', 'SEMANTIC_V3_4_COMPACT') AND document.document_type IN ('ATTRACTION_TICKET', 'TOUR_BOOKING'))
                OR (NEW.extraction_contract = 'FLIGHT_V1' AND document.document_type = 'FLIGHT_CONFIRMATION')
                OR (NEW.extraction_contract = 'UNSUPPORTED' AND (document.document_type IS NULL
                    OR document.document_type NOT IN ('ATTRACTION_TICKET', 'TOUR_BOOKING', 'FLIGHT_CONFIRMATION'))))
        ) THEN
            RAISE EXCEPTION 'Extraction resolution requires an active matching job and category';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
COMMIT;
-- Rollback: stop creating compact jobs but retain compatible consumers/readers for all compact
-- jobs and saved extractions. Never reset job decisions or roll back to readers rejecting their
-- provenance identity. The additive allowlist can remain; no data rollback is required.
