-- Manual additive migration. No backfill; immutable extraction provenance is unchanged.
BEGIN;
SET LOCAL lock_timeout='3s';
ALTER TABLE reservations
    ADD COLUMN required_arrival_at timestamp with time zone,
    ADD COLUMN arrival_reviewed_at timestamp with time zone,
    ADD CONSTRAINT reservation_reviewed_arrival_pair CHECK
        ((required_arrival_at IS NULL) = (arrival_reviewed_at IS NULL));
COMMIT;
