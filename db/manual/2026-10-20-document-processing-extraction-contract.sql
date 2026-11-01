-- PREPARED ONLY: apply separately, before activating the new API/worker code.
-- Existing rows remain NULL (legacy); old producers omit this nullable column.
-- Keep old producers on legacy until all consuming workers support AUTO_V3_4.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE document_processing_outbox
    ADD COLUMN extraction_contract varchar(32),
    ADD CONSTRAINT document_processing_extraction_contract_valid CHECK (
        extraction_contract IS NULL OR extraction_contract IN
            ('LEGACY', 'SEMANTIC_V2', 'SEMANTIC_V3_4', 'AUTO_V3_4', 'FLIGHT_V1', 'UNSUPPORTED')),
    ADD CONSTRAINT document_processing_contract_job_required CHECK (
        extraction_contract IS NULL OR processing_job_id IS NOT NULL);

CREATE FUNCTION prevent_document_processing_contract_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.extraction_contract IS DISTINCT FROM OLD.extraction_contract THEN
        -- Only pending automatic jobs may resolve, exactly once. NULL is never pending.
        IF OLD.extraction_contract IS DISTINCT FROM 'AUTO_V3_4'
           OR NEW.extraction_contract IS NULL
           OR NEW.extraction_contract NOT IN ('SEMANTIC_V3_4', 'FLIGHT_V1', 'UNSUPPORTED')
           OR NEW.processing_job_id IS DISTINCT FROM OLD.processing_job_id
           OR NEW.document_id IS DISTINCT FROM OLD.document_id THEN
            RAISE EXCEPTION 'Resolved or historical processing contract is immutable';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM travel_documents document
            WHERE document.id = NEW.document_id AND document.processing_job_id = NEW.processing_job_id
              AND document.processing_status = 'PROCESSING' AND document.processing_attempt_id IS NOT NULL
              AND ((NEW.extraction_contract = 'SEMANTIC_V3_4' AND document.document_type IN ('ATTRACTION_TICKET', 'TOUR_BOOKING'))
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
CREATE TRIGGER document_processing_contract_immutable
    BEFORE UPDATE OF extraction_contract ON document_processing_outbox
    FOR EACH ROW EXECUTE FUNCTION prevent_document_processing_contract_change();
COMMIT;
-- Rollback: retain the additive schema. Old workers cannot honor pending/resolved
-- nonlegacy jobs; use compatible workers or pause processing, never reset decisions.
