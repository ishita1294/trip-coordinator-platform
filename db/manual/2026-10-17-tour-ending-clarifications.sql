-- Apply after 2026-10-17-tour-correction-reviews.sql, before compatible ECS and Lambda.
-- No backfill, source rewrite, geocoding, or reclassification of existing bookings.
BEGIN;
ALTER TABLE reservations
 ADD COLUMN endpoint_clarification_actor_id bigint REFERENCES users(id),
 ADD COLUMN endpoint_clarification_at timestamptz,
 ADD COLUMN endpoint_clarification_choice varchar(255),
 ADD COLUMN endpoint_clarification_name varchar(255),
 ADD COLUMN endpoint_clarification_address varchar(1000),
 ADD CONSTRAINT initial_endpoint_clarification CHECK (
  (endpoint_clarification_actor_id IS NULL AND endpoint_clarification_at IS NULL AND endpoint_clarification_choice IS NULL AND endpoint_clarification_name IS NULL AND endpoint_clarification_address IS NULL)
  OR (endpoint_clarification_actor_id IS NOT NULL AND endpoint_clarification_at IS NOT NULL
   AND endpoint_clarification_choice IS NOT NULL AND endpoint_clarification_choice IN ('UNKNOWN','SAME_AS_MEETING_POINT','LOCATION')
   AND confirmed_extraction_id IS NOT NULL));
CREATE FUNCTION protect_initial_endpoint_clarification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.endpoint_clarification_actor_id,NEW.endpoint_clarification_at,NEW.endpoint_clarification_choice,NEW.endpoint_clarification_name,NEW.endpoint_clarification_address)
  IS DISTINCT FROM ROW(OLD.endpoint_clarification_actor_id,OLD.endpoint_clarification_at,OLD.endpoint_clarification_choice,OLD.endpoint_clarification_name,OLD.endpoint_clarification_address) THEN
  RAISE EXCEPTION 'Initial endpoint clarification provenance is immutable';
 END IF;
 IF NEW.endpoint_clarification_actor_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM travel_document_extractions e JOIN travel_documents d ON d.id=e.travel_document_id
  WHERE e.id=NEW.confirmed_extraction_id AND d.trip_id=NEW.trip_id AND d.uploaded_by_user_id=NEW.endpoint_clarification_actor_id) THEN
  RAISE EXCEPTION 'Clarification actor does not belong to confirmed source';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER initial_endpoint_clarification_immutable BEFORE INSERT OR UPDATE ON reservations
 FOR EACH ROW EXECUTE FUNCTION protect_initial_endpoint_clarification();
COMMIT;
