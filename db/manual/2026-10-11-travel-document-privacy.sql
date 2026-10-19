-- Additive; legacy ownership is intentionally unknown. No automatic ownership backfill.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE travel_documents ADD COLUMN uploaded_by_user_id bigint;
ALTER TABLE travel_documents ADD CONSTRAINT fk_document_uploader FOREIGN KEY (uploaded_by_user_id) REFERENCES users(id);
CREATE INDEX ix_document_trip_uploader ON travel_documents(trip_id, uploaded_by_user_id);
CREATE FUNCTION protect_document_uploader() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.uploaded_by_user_id IS NOT NULL AND NEW.uploaded_by_user_id IS DISTINCT FROM OLD.uploaded_by_user_id THEN
        RAISE EXCEPTION 'Established document ownership is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER document_uploader_immutable BEFORE UPDATE ON travel_documents FOR EACH ROW EXECUTE FUNCTION protect_document_uploader();
COMMIT;
