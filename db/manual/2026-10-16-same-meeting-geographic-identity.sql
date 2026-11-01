-- Forward-only constraint expansion. No backfill and no provider-content persistence.
-- Apply before the compatible backend; see docs/verification/same-meeting-geographic-identity.md.
BEGIN;
SET LOCAL lock_timeout='3s';
ALTER TABLE reservations ADD CONSTRAINT activity_endpoint_geography_v2 CHECK (COALESCE((
 (ending_place_id IS NULL AND ending_location_source IS DISTINCT FROM 'USER_CONFIRMED_PLACE_ID')
 OR
 (ending_place_id IS NOT NULL AND ending_place_id ~ '^[A-Za-z0-9_-]{1,255}$' AND (
   (ending_location_source='USER_CONFIRMED_PLACE_ID' AND ending_location_name IS NULL AND ending_location_address IS NULL AND ending_location_latitude IS NULL AND ending_location_longitude IS NULL)
   OR
   (ending_location_source='USER_CONFIRMED_SAME_AS_MEETING'
    AND (nullif(btrim(ending_location_name),'') IS NOT NULL OR nullif(btrim(ending_location_address),'') IS NOT NULL)
    AND ending_location_name IS NOT DISTINCT FROM location_name
    AND ending_location_address IS NOT DISTINCT FROM location_address
    AND ending_location_latitude IS NOT DISTINCT FROM location_latitude
    AND ending_location_longitude IS NOT DISTINCT FROM location_longitude)
  ))
),false)) NOT VALID;
ALTER TABLE reservations VALIDATE CONSTRAINT activity_endpoint_geography_v2;
ALTER TABLE reservations DROP CONSTRAINT activity_endpoint_google_id_only;
ALTER TABLE reservations RENAME CONSTRAINT activity_endpoint_geography_v2 TO activity_endpoint_google_id_only;
COMMIT;
