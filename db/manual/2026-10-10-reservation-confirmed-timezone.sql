-- REVIEW ONLY: apply separately before deploying with ddl-auto=validate.
-- No existing reservations are changed. No timezone is inferred or backfilled.
BEGIN;

ALTER TABLE reservations
    ADD COLUMN schedule_time_zone varchar(100),
    ADD COLUMN confirmed_extraction_id bigint,
    ADD COLUMN confirmed_booking_index integer;

ALTER TABLE reservations
    ADD CONSTRAINT fk_reservation_confirmed_extraction
        FOREIGN KEY (confirmed_extraction_id) REFERENCES travel_document_extractions(id),
    ADD CONSTRAINT ck_reservation_confirmed_booking
        CHECK ((confirmed_extraction_id IS NULL AND confirmed_booking_index IS NULL)
            OR (confirmed_extraction_id IS NOT NULL AND confirmed_booking_index IS NOT NULL
                AND confirmed_booking_index >= 0));

-- Once recorded, the exact extraction/booking evidence cannot be rewritten.
-- The current authoritative timezone is separately correctable; it need not match
-- the source document and does not require document provenance.
-- A separately reviewed legacy backfill may populate previously null evidence once.
CREATE FUNCTION protect_reservation_confirmed_schedule() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.confirmed_extraction_id IS NOT NULL AND
        (NEW.confirmed_extraction_id IS DISTINCT FROM OLD.confirmed_extraction_id
         OR NEW.confirmed_booking_index IS DISTINCT FROM OLD.confirmed_booking_index) THEN
        RAISE EXCEPTION 'Confirmed reservation source provenance is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reservation_confirmed_schedule_immutable
BEFORE UPDATE ON reservations
FOR EACH ROW EXECUTE FUNCTION protect_reservation_confirmed_schedule();

COMMIT;
