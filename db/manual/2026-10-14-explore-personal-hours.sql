-- Additive; manual activation required. No existing booking or itinerary updates.
BEGIN;
SET LOCAL lock_timeout='3s';
CREATE TABLE explore_personal_hours (
    trip_id bigint NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    preferences jsonb NOT NULL CHECK (jsonb_typeof(preferences)='object'),
    version bigint NOT NULL CHECK(version>0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(trip_id,user_id)
);
COMMIT;
