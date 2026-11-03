-- Baseline of the live AWS-DEV schema (structure only, no data), taken read-only with pg_dump on 2026-11-03.
-- Existing databases are marked as already being at version 1 and this file is not run on them;
-- an empty database builds the whole schema from here. Never edit this file; add V2, V3, ... for changes.
--
-- PostgreSQL database dump
--

-- Dumped from database version 16.13
-- Dumped by pg_dump version 18.6

--
-- Name: explore_google_phase_rpc(text, text, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.explore_google_phase_rpc(s text, command text, payload jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', 'public'
    SET "TimeZone" TO 'UTC'
    SET lock_timeout TO '2s'
    AS $_$
DECLARE
 p public.explore_provider_usage_policy_revisions; r public.explore_provider_usage_reservations;
 w public.explore_provider_usage_windows; o public.explore_provider_usage_operations;
 t timestamptz; rid uuid; req uuid; plan jsonb; item jsonb; completions jsonb;
 ids bigint[]; requested uuid[]; amount bigint := 0; released bigint := 0;
 hour_id bigint; day_id bigint; month_id bigint; boundary timestamptz; expires timestamptz;
 blocked_names text[] := ARRAY[]::text[]; fence uuid; results jsonb := '[]'; n integer; st text;
BEGIN
 IF s !~ '^[A-Za-z0-9_.:-]{1,96}$' OR command NOT IN ('RESERVE','AUTHORIZE','SETTLE') THEN RAISE EXCEPTION 'LEDGER_INVALID'; END IF;
 -- Identical lock order to JDBC/recovery/policy activation: policy -> windows -> reservation -> operations.
 PERFORM 1 FROM public.explore_provider_usage_policies WHERE budget_scope=s FOR UPDATE;
 SELECT v.* INTO p FROM public.explore_provider_usage_policy_revisions v JOIN public.explore_provider_usage_policies a ON a.active_revision_id=v.id AND a.budget_scope=v.budget_scope WHERE a.budget_scope=s;
 IF NOT FOUND THEN RAISE EXCEPTION 'POLICY_MISSING'; END IF;
 IF command <> 'SETTLE' THEN
  IF payload->>'book'<>p.price_book_version OR payload->>'digest'<>p.price_book_digest OR p.protocol_version<>1 THEN RAISE EXCEPTION 'POLICY_CHANGED'; END IF;
  IF p.accounting_mode NOT IN ('TRACK_ONLY','OPTIONAL_HARD_CAP') THEN RAISE EXCEPTION 'POLICY_CHANGED'; END IF;
  IF NOT p.admissions_enabled THEN RAISE EXCEPTION 'POLICY_PAUSED'; END IF;
  t:=clock_timestamp();
  IF t<p.effective_at OR t>=p.admission_valid_until THEN RAISE EXCEPTION 'PRICE_BOOK_EXPIRED'; END IF;
  IF payload->>'leasePid' IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM pg_locks l WHERE l.pid=(payload->>'leasePid')::int AND l.locktype='advisory' AND l.granted
    AND l.classid::bigint=((hashtextextended('explore-capacity:'||s,734621)>>32)&4294967295)
    AND l.objid::bigint=(hashtextextended('explore-capacity:'||s,734621)&4294967295) AND l.objsubid=1
  ) THEN RAISE EXCEPTION 'TOPOLOGY_LOST'; END IF;
 END IF;
 IF command='RESERVE' THEN
  req:=(payload->>'requestId')::uuid; plan:=payload->'slots';
  IF jsonb_array_length(plan) NOT BETWEEN 1 AND 128 OR payload->>'phaseKey' !~ '^[A-Za-z0-9_-]{1,96}$'
    OR (SELECT count(DISTINCT x->>'key') FROM jsonb_array_elements(plan) x)<>jsonb_array_length(plan) THEN RAISE EXCEPTION 'LEDGER_INVALID'; END IF;
  FOR item IN SELECT * FROM jsonb_array_elements(plan) LOOP
   IF item->>'key' !~ '^[A-Za-z0-9_-]{1,96}$' OR (item->>'price')::bigint<=0
    OR NOT (payload->'prices' ? (item->>'sku'))
    OR item->>'kind'<>payload->'prices'->(item->>'sku')->>'kind'
    OR (item->>'price')::bigint<>(payload->'prices'->(item->>'sku')->>'price')::bigint THEN RAISE EXCEPTION 'UNSUPPORTED_SKU'; END IF;
   amount:=amount+(item->>'price')::bigint;
  END LOOP;
  SELECT * INTO r FROM public.explore_provider_usage_reservations WHERE request_id=req AND phase_key=payload->>'phaseKey';
  IF FOUND THEN
   ids:=ARRAY[r.hourly_window_id,r.daily_window_id,r.monthly_window_id];
   PERFORM 1 FROM public.explore_provider_usage_windows WHERE id=ANY(ids) AND budget_scope=s ORDER BY id FOR UPDATE;
   GET DIAGNOSTICS n=ROW_COUNT; IF n<>3 THEN RAISE EXCEPTION 'LEDGER_INVALID'; END IF;
   SELECT * INTO STRICT r FROM public.explore_provider_usage_reservations WHERE id=r.id FOR UPDATE;
   PERFORM 1 FROM public.explore_provider_usage_operations WHERE reservation_id=r.id ORDER BY id FOR UPDATE;
   IF r.operation_plan_digest<>payload->>'planDigest' THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT'; END IF;
   RETURN jsonb_build_object('id',r.id,'operations',(SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM public.explore_provider_usage_operations x WHERE x.reservation_id=r.id));
  END IF;
  t:=clock_timestamp();
  INSERT INTO public.explore_provider_usage_windows(policy_key,budget_scope,starts_at,ends_at,applied_policy_revision_id,limit_micros)
   VALUES('GOOGLE_UTC_HOUR',s,date_trunc('hour',t),date_trunc('hour',t)+interval '1 hour',p.id,p.hourly_limit_micros),
    ('GOOGLE_UTC_DAY',s,date_trunc('day',t),date_trunc('day',t)+interval '1 day',p.id,p.daily_limit_micros),
    ('GOOGLE_UTC_MONTH',s,date_trunc('month',t),date_trunc('month',t)+interval '1 month',p.id,p.monthly_limit_micros)
   ON CONFLICT(policy_key,budget_scope,starts_at) DO NOTHING;
  PERFORM 1 FROM public.explore_provider_usage_windows WHERE budget_scope=s AND
   ((policy_key='GOOGLE_UTC_HOUR' AND starts_at=date_trunc('hour',t)) OR (policy_key='GOOGLE_UTC_DAY' AND starts_at=date_trunc('day',t)) OR (policy_key='GOOGLE_UTC_MONTH' AND starts_at=date_trunc('month',t))) ORDER BY id FOR UPDATE;
  GET DIAGNOSTICS n=ROW_COUNT; IF n<>3 THEN RAISE EXCEPTION 'LEDGER_INVALID'; END IF;
  SELECT id INTO hour_id FROM public.explore_provider_usage_windows WHERE budget_scope=s AND policy_key='GOOGLE_UTC_HOUR' AND starts_at=date_trunc('hour',t);
  SELECT id INTO day_id FROM public.explore_provider_usage_windows WHERE budget_scope=s AND policy_key='GOOGLE_UTC_DAY' AND starts_at=date_trunc('day',t);
  SELECT id INTO month_id FROM public.explore_provider_usage_windows WHERE budget_scope=s AND policy_key='GOOGLE_UTC_MONTH' AND starts_at=date_trunc('month',t);
  ids:=ARRAY[hour_id,day_id,month_id];
 ELSE
  rid:=(payload->>'phaseId')::uuid;
  SELECT * INTO r FROM public.explore_provider_usage_reservations WHERE id=rid;
  IF NOT FOUND THEN RAISE EXCEPTION 'PHASE_MISSING'; END IF;
  ids:=ARRAY[r.hourly_window_id,r.daily_window_id,r.monthly_window_id];
  PERFORM 1 FROM public.explore_provider_usage_windows WHERE id=ANY(ids) AND budget_scope=s ORDER BY id FOR UPDATE;
  GET DIAGNOSTICS n=ROW_COUNT; IF n<>3 THEN RAISE EXCEPTION 'LEDGER_INVALID'; END IF;
  SELECT * INTO STRICT r FROM public.explore_provider_usage_reservations WHERE id=rid FOR UPDATE;
  PERFORM 1 FROM public.explore_provider_usage_operations WHERE reservation_id=rid ORDER BY id FOR UPDATE;
 END IF;
 t:=clock_timestamp();
 IF command<>'SETTLE' THEN
  IF t<p.effective_at OR t>=p.admission_valid_until THEN RAISE EXCEPTION 'PRICE_BOOK_EXPIRED'; END IF;
  FOR w IN SELECT * FROM public.explore_provider_usage_windows WHERE id=ANY(ids) LOOP
   IF w.applied_policy_revision_id<>p.id OR t>=w.ends_at THEN RAISE EXCEPTION 'POLICY_CHANGED'; END IF;
   IF w.limit_micros<>(CASE w.policy_key WHEN 'GOOGLE_UTC_HOUR' THEN p.hourly_limit_micros WHEN 'GOOGLE_UTC_DAY' THEN p.daily_limit_micros WHEN 'GOOGLE_UTC_MONTH' THEN p.monthly_limit_micros ELSE -1 END) THEN RAISE EXCEPTION 'LEDGER_INVALID'; END IF;
   IF p.accounting_mode='OPTIONAL_HARD_CAP' AND w.charged_micros::numeric+w.reserved_micros+w.uncertainty_hold_micros+(CASE WHEN command='RESERVE' THEN amount ELSE 0 END)>w.limit_micros THEN boundary:=greatest(boundary,w.ends_at);blocked_names:=array_append(blocked_names,replace(w.policy_key,'GOOGLE_UTC_','')); END IF;
  END LOOP;
  IF boundary IS NOT NULL THEN RAISE EXCEPTION 'COST_BUDGET_EXHAUSTED|%|%',to_char(boundary,'YYYY-MM-DD"T"HH24:MI:SS"Z"'),array_to_string(blocked_names,','); END IF;
 END IF;
 IF command='RESERVE' THEN
  rid:=gen_random_uuid();expires:=least(t+interval '45 seconds',date_trunc('hour',t)+interval '1 hour',p.admission_valid_until);
  INSERT INTO public.explore_provider_usage_reservations(id,request_id,phase_key,policy_revision_id,operation_plan_digest,hourly_window_id,daily_window_id,monthly_window_id,expires_at)
   VALUES(rid,req,payload->>'phaseKey',p.id,payload->>'planDigest',hour_id,day_id,month_id,expires);
  INSERT INTO public.explore_provider_usage_operations(id,reservation_id,operation_key,operation_kind,sku,price_book_version,billable_units,unit_price_micros)
   SELECT gen_random_uuid(),rid,x->>'key',x->>'kind',x->>'sku',p.price_book_version,1,(x->>'price')::bigint FROM jsonb_array_elements(plan) x;
  UPDATE public.explore_provider_usage_windows SET reserved_micros=reserved_micros+amount WHERE id=ANY(ids);
  RETURN jsonb_build_object('id',rid,'operations',(SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM public.explore_provider_usage_operations x WHERE x.reservation_id=rid));
 ELSIF command='AUTHORIZE' THEN
  IF r.state<>'OPEN' OR t>=r.expires_at THEN RAISE EXCEPTION 'PHASE_EXPIRED'; END IF;
  IF EXISTS(SELECT 1 FROM public.explore_provider_usage_policy_revisions x WHERE x.id=r.policy_revision_id AND (x.protocol_version<>p.protocol_version OR x.price_book_version<>p.price_book_version OR x.price_book_digest<>p.price_book_digest)) THEN RAISE EXCEPTION 'POLICY_CHANGED'; END IF;
  SELECT array_agg(x::uuid) INTO requested FROM jsonb_array_elements_text(payload->'operationIds') x;
  IF cardinality(requested) NOT BETWEEN 1 AND 128 OR (SELECT count(DISTINCT x) FROM unnest(requested) x)<>cardinality(requested) THEN RAISE EXCEPTION 'DUPLICATE_DISPATCH'; END IF;
  FOR rid IN SELECT unnest(requested) LOOP
   SELECT * INTO o FROM public.explore_provider_usage_operations WHERE id=rid AND reservation_id=r.id;
   IF NOT FOUND OR o.state<>'RESERVED' THEN RAISE EXCEPTION 'DUPLICATE_DISPATCH'; END IF;
   IF o.price_book_version<>p.price_book_version OR o.billable_units<>1 OR NOT(payload->'prices' ? o.sku)
    OR o.operation_kind<>payload->'prices'->o.sku->>'kind' OR o.unit_price_micros<>(payload->'prices'->o.sku->>'price')::bigint OR o.estimated_cost_micros<>o.unit_price_micros THEN RAISE EXCEPTION 'UNSUPPORTED_SKU'; END IF;
   amount:=amount+o.estimated_cost_micros;fence:=gen_random_uuid();
   UPDATE public.explore_provider_usage_operations SET state='DISPATCH_AUTHORIZED',dispatch_fence=fence,authorized_at=t WHERE id=o.id;
   results:=results||jsonb_build_array(jsonb_build_object('operationId',o.id,'fence',fence));
  END LOOP;
  UPDATE public.explore_provider_usage_windows SET reserved_micros=reserved_micros-amount,charged_micros=charged_micros+amount WHERE id=ANY(ids);
  RETURN results;
 ELSE
  completions:=payload->'completions';
  IF jsonb_array_length(completions)<>(SELECT count(DISTINCT x->>'operationId') FROM jsonb_array_elements(completions) x) THEN RAISE EXCEPTION 'DUPLICATE_COMPLETION'; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(completions) x WHERE NOT EXISTS(SELECT 1 FROM public.explore_provider_usage_operations op WHERE op.id=(x->>'operationId')::uuid AND op.reservation_id=r.id)) THEN RAISE EXCEPTION 'WRONG_FENCE'; END IF;
  FOR o IN SELECT * FROM public.explore_provider_usage_operations WHERE reservation_id=r.id ORDER BY id LOOP
   SELECT x INTO item FROM jsonb_array_elements(completions) x WHERE x->>'operationId'=o.id::text;
   IF item IS NOT NULL AND (item->>'fence')::uuid IS DISTINCT FROM o.dispatch_fence THEN RAISE EXCEPTION 'WRONG_FENCE'; END IF;
   IF o.state='RESERVED' THEN released:=released+o.estimated_cost_micros;st:='RELEASED';
   ELSIF o.state IN('DISPATCH_AUTHORIZED','UNKNOWN') THEN st:=CASE WHEN item IS NULL THEN 'UNKNOWN' WHEN (item->>'succeeded')::boolean THEN 'SUCCEEDED' ELSE 'FAILED' END;
   ELSE CONTINUE; END IF;
   UPDATE public.explore_provider_usage_operations SET state=st,completed_at=t WHERE id=o.id;
  END LOOP;
  UPDATE public.explore_provider_usage_windows SET reserved_micros=reserved_micros-released WHERE id=ANY(ids);
  IF r.state='OPEN' THEN UPDATE public.explore_provider_usage_reservations SET state='CLOSED',settled_at=t WHERE id=r.id; END IF;
  RETURN jsonb_build_object('settled',true);
 END IF;
END $_$;

--
-- Name: prevent_document_processing_contract_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_document_processing_contract_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

--
-- Name: protect_activity_endpoint_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_activity_endpoint_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.live_reservation_id IS NOT NULL AND NEW.live_reservation_id IS NULL
   AND (to_jsonb(NEW)-'live_reservation_id')=(to_jsonb(OLD)-'live_reservation_id')
   AND NOT EXISTS(SELECT 1 FROM reservations r WHERE r.id=OLD.live_reservation_id) THEN
   -- The FK's ON DELETE SET NULL runs after the parent row is deleted. No ordinary
   -- update may detach a live parent or alter substantive evidence, even after deletion.
   RETURN NEW;
 END IF;
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Endpoint history is immutable'; END IF;
 IF NEW.live_reservation_id IS NOT NULL AND NEW.live_reservation_id<>NEW.reservation_id THEN
   RAISE EXCEPTION 'Live endpoint association must match historical identity';
 END IF;
 NEW.live_reservation_id=NEW.reservation_id;
 IF NOT EXISTS(SELECT 1 FROM reservations r WHERE r.id=NEW.reservation_id AND r.trip_id=NEW.trip_id
   AND r.confirmed_extraction_id IS NOT DISTINCT FROM NEW.extraction_id) THEN
   RAISE EXCEPTION 'Endpoint history must match the reservation and source';
 END IF;
 RETURN NEW;
END $$;

--
-- Name: protect_booking_confirmation_receipt(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_booking_confirmation_receipt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  -- Normal receipt deletion is prohibited. Source erasure cascades only after its
  -- parent disappears; existing extraction-erasure safeguards still govern that operation.
  IF NOT EXISTS(SELECT 1 FROM travel_document_extractions e WHERE e.id=OLD.extraction_id) THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'Booking confirmation receipts are immutable';
 END IF;
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Booking confirmation receipts are immutable'; END IF;
 IF NOT EXISTS(
  SELECT 1 FROM travel_document_extractions e JOIN travel_documents d ON d.id=e.travel_document_id
  JOIN reservations r ON r.id=NEW.reservation_id
  WHERE e.id=NEW.extraction_id AND d.uploaded_by_user_id=NEW.confirmed_by
   AND r.trip_id=d.trip_id AND r.confirmed_extraction_id=e.id AND r.confirmed_booking_index=NEW.original_booking_index
   AND r.correction_review_id IS NOT DISTINCT FROM NEW.accepted_review_id
   AND jsonb_typeof(e.extracted_data::jsonb->'bookings')='array'
   AND NEW.original_booking_index<jsonb_array_length(e.extracted_data::jsonb->'bookings')
   AND NEW.extraction_sha256=encode(sha256(convert_to(e.extracted_data,'UTF8')),'hex')
   AND NEW.schema_version=coalesce((e.extracted_data::jsonb->>'schemaVersion')::integer,1)
   AND NEW.prompt_version IS NOT DISTINCT FROM (e.extracted_data::jsonb->'provenance'->>'promptVersion')
 ) THEN RAISE EXCEPTION 'Receipt must match the confirmed booking, actor and immutable source'; END IF;
 IF NEW.accepted_review_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM tour_correction_reviews c WHERE c.id=NEW.accepted_review_id
   AND c.extraction_id=NEW.extraction_id AND c.actor_id=NEW.confirmed_by AND c.accepted_by=NEW.confirmed_by
   AND c.accepted_at IS NOT NULL AND c.version=NEW.accepted_review_version AND c.extraction_sha256=NEW.extraction_sha256
 ) THEN RAISE EXCEPTION 'Receipt must identify the exact accepted source review'; END IF;
 RETURN NEW;
END $$;

--
-- Name: protect_current_endpoint_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_current_endpoint_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.endpoint_update_id IS NOT NULL AND (TG_OP='INSERT' OR NEW.endpoint_update_id IS DISTINCT FROM OLD.endpoint_update_id)
 AND NOT EXISTS(SELECT 1 FROM activity_endpoint_history h WHERE h.id=NEW.endpoint_update_id AND h.reservation_id=NEW.id
   AND h.trip_id=NEW.trip_id AND h.endpoint_version=NEW.ending_location_version) THEN
  RAISE EXCEPTION 'Current endpoint history does not match the booking version';
 END IF;
 IF TG_OP='UPDATE' AND OLD.endpoint_update_id IS NOT NULL AND NEW.endpoint_update_id IS NULL THEN
  RAISE EXCEPTION 'Current endpoint history cannot be discarded';
 END IF;
 RETURN NEW;
END $$;

--
-- Name: protect_document_uploader(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_document_uploader() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.uploaded_by_user_id IS NOT NULL AND NEW.uploaded_by_user_id IS DISTINCT FROM OLD.uploaded_by_user_id THEN
        RAISE EXCEPTION 'Established document ownership is immutable';
    END IF;
    RETURN NEW;
END;
$$;

--
-- Name: protect_explore_usage_operation_snapshot(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_explore_usage_operation_snapshot() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF ROW(NEW.id, NEW.reservation_id, NEW.operation_key, NEW.operation_kind,
           NEW.sku, NEW.price_book_version, NEW.billable_units, NEW.unit_price_micros, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.reservation_id, OLD.operation_key, OLD.operation_kind,
           OLD.sku, OLD.price_book_version, OLD.billable_units, OLD.unit_price_micros, OLD.created_at) THEN
        RAISE EXCEPTION 'Provider usage operation rate snapshots are immutable';
    END IF;
    RETURN NEW;
END;
$$;

--
-- Name: protect_explore_usage_policy_revision(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_explore_usage_policy_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Provider usage policy revisions are append-only';
END;
$$;

--
-- Name: protect_initial_endpoint_clarification(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_initial_endpoint_clarification() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

--
-- Name: protect_original_tour_extraction(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_original_tour_extraction() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 RAISE EXCEPTION 'Original extraction records are immutable';
END $$;

--
-- Name: protect_reservation_confirmed_schedule(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_reservation_confirmed_schedule() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.confirmed_extraction_id IS NOT NULL AND
        (NEW.confirmed_extraction_id IS DISTINCT FROM OLD.confirmed_extraction_id
         OR NEW.confirmed_booking_index IS DISTINCT FROM OLD.confirmed_booking_index) THEN
        RAISE EXCEPTION 'Confirmed reservation source provenance is immutable';
    END IF;
    RETURN NEW;
END;
$$;

--
-- Name: protect_tour_correction_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_tour_correction_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Correction history is append-only'; END IF;
 IF (to_jsonb(NEW)-ARRAY['accepted_at','accepted_by','version']) IS DISTINCT FROM
    (to_jsonb(OLD)-ARRAY['accepted_at','accepted_by','version'])
    OR OLD.accepted_at IS NOT NULL OR NEW.accepted_at IS NULL OR NEW.accepted_by IS DISTINCT FROM OLD.actor_id
    OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'Immutable correction assessment or invalid acceptance transition';
 END IF;
 RETURN NEW;
END $$;

--
-- Name: protect_tour_correction_link(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_tour_correction_link() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.correction_review_id IS DISTINCT FROM NEW.correction_review_id THEN
   RAISE EXCEPTION 'Confirmed correction provenance is immutable';
 END IF;
 IF NEW.correction_review_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM tour_correction_reviews c JOIN travel_document_extractions e ON e.id=c.extraction_id
   JOIN travel_documents d ON d.id=e.travel_document_id
   WHERE c.id=NEW.correction_review_id AND c.extraction_id=NEW.confirmed_extraction_id
   AND c.document_id=d.id AND d.trip_id=NEW.trip_id AND c.accepted_at IS NOT NULL) THEN
   RAISE EXCEPTION 'Correction does not belong to the confirmed source';
 END IF;
 RETURN NEW;
END $$;

--
-- Name: protect_user_auth_identity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_user_auth_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.issuer IS DISTINCT FROM OLD.issuer OR NEW.subject IS DISTINCT FROM OLD.subject
       OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.linked_at IS DISTINCT FROM OLD.linked_at THEN
        RAISE EXCEPTION 'Established authentication identity cannot be reassigned';
    END IF;
    RETURN NEW;
END;
$$;

--
-- Name: activities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activities (
    id bigint NOT NULL,
    category character varying(255),
    description character varying(255),
    estimated_cost numeric(38,2),
    estimated_duration_minutes integer,
    name character varying(255) NOT NULL,
    status character varying(255),
    destination_id bigint NOT NULL,
    trip_id bigint NOT NULL,
    CONSTRAINT activities_category_check CHECK (((category)::text = ANY ((ARRAY['FOOD'::character varying, 'CULTURE'::character varying, 'HISTORY'::character varying, 'NATURE'::character varying, 'SHOPPING'::character varying, 'NIGHTLIFE'::character varying, 'ADVENTURE'::character varying, 'RELAXATION'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT activities_status_check CHECK (((status)::text = ANY ((ARRAY['SUGGESTED'::character varying, 'SHORTLISTED'::character varying, 'PLANNED'::character varying, 'REJECTED'::character varying])::text[])))
);

--
-- Name: activities_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.activities ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.activities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: activity_endpoint_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activity_endpoint_history (
    id bigint NOT NULL,
    reservation_id bigint NOT NULL,
    live_reservation_id bigint,
    trip_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    extraction_id bigint,
    reported_at timestamp with time zone NOT NULL,
    previous_version bigint NOT NULL,
    endpoint_version bigint NOT NULL,
    kind character varying(255) NOT NULL,
    choice character varying(255) NOT NULL,
    reported_name character varying(255),
    reported_address character varying(1000),
    previous_json text NOT NULL,
    current_json text NOT NULL,
    CONSTRAINT activity_endpoint_history_check CHECK (((live_reservation_id IS NULL) OR (live_reservation_id = reservation_id))),
    CONSTRAINT activity_endpoint_history_check1 CHECK (((previous_version >= 0) AND (endpoint_version = (previous_version + 1)))),
    CONSTRAINT activity_endpoint_history_check2 CHECK (((jsonb_typeof((previous_json)::jsonb) = 'object'::text) AND (jsonb_typeof((current_json)::jsonb) = 'object'::text))),
    CONSTRAINT activity_endpoint_history_check3 CHECK ((((choice)::text <> 'UNKNOWN'::text) OR ((reported_name IS NULL) AND (reported_address IS NULL)))),
    CONSTRAINT activity_endpoint_history_choice_check CHECK (((choice)::text = ANY ((ARRAY['UNKNOWN'::character varying, 'SAME_AS_MEETING_POINT'::character varying, 'LOCATION'::character varying])::text[]))),
    CONSTRAINT activity_endpoint_history_kind_check CHECK (((kind)::text = ANY ((ARRAY['USER_REPORTED_ENDPOINT_AMENDMENT'::character varying, 'GEOGRAPHIC_ENRICHMENT'::character varying])::text[])))
);

--
-- Name: activity_endpoint_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.activity_endpoint_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.activity_endpoint_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: ai_conversation_turns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_conversation_turns (
    id bigint NOT NULL,
    conversation_id uuid NOT NULL,
    request_id uuid NOT NULL,
    content_sha256 character varying(64) NOT NULL,
    status character varying(255) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    user_sequence bigint NOT NULL,
    assistant_sequence bigint,
    error_code character varying(60),
    CONSTRAINT ai_conversation_turns_check CHECK ((expires_at > created_at)),
    CONSTRAINT ai_conversation_turns_check1 CHECK ((assistant_sequence > user_sequence)),
    CONSTRAINT ai_conversation_turns_check2 CHECK (((((status)::text = 'RUNNING'::text) AND (completed_at IS NULL) AND (assistant_sequence IS NULL) AND (error_code IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL) AND (assistant_sequence IS NOT NULL) AND (error_code IS NULL)) OR (((status)::text = ANY ((ARRAY['FAILED'::character varying, 'INTERRUPTED'::character varying])::text[])) AND (completed_at IS NOT NULL) AND (assistant_sequence IS NULL) AND (error_code IS NOT NULL)))),
    CONSTRAINT ai_conversation_turns_content_sha256_check CHECK (((content_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ai_conversation_turns_status_check CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'INTERRUPTED'::character varying])::text[]))),
    CONSTRAINT ai_conversation_turns_user_sequence_check CHECK ((user_sequence > 0))
);

--
-- Name: ai_conversation_turns_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.ai_conversation_turns ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.ai_conversation_turns_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: ai_conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_conversations (
    id uuid NOT NULL,
    owner_id bigint NOT NULL,
    trip_id bigint,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    context_json jsonb NOT NULL,
    last_sequence bigint DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    active_turn_id uuid,
    CONSTRAINT ai_conversations_context_json_check CHECK ((jsonb_typeof(context_json) = 'object'::text)),
    CONSTRAINT ai_conversations_last_sequence_check CHECK ((last_sequence >= 0))
);

--
-- Name: ai_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_messages (
    id bigint NOT NULL,
    conversation_id uuid NOT NULL,
    sequence_number bigint NOT NULL,
    role character varying(255) NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ai_messages_content_check CHECK (((length(TRIM(BOTH FROM content)) > 0) AND (length(content) <= 16000))),
    CONSTRAINT ai_messages_role_check CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying])::text[]))),
    CONSTRAINT ai_messages_sequence_number_check CHECK ((sequence_number > 0))
);

--
-- Name: ai_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.ai_messages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.ai_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: booking_confirmation_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.booking_confirmation_receipts (
    id bigint NOT NULL,
    extraction_id bigint NOT NULL,
    original_booking_index integer NOT NULL,
    confirmed_by bigint NOT NULL,
    confirmed_at timestamp with time zone NOT NULL,
    reservation_id bigint NOT NULL,
    extraction_sha256 character varying(64) NOT NULL,
    schema_version integer NOT NULL,
    prompt_version character varying(100),
    accepted_review_id bigint,
    accepted_review_version bigint,
    CONSTRAINT booking_confirmation_receipts_check CHECK ((((schema_version = 1) AND (prompt_version IS NULL)) OR ((schema_version = ANY (ARRAY[2, 3])) AND (prompt_version IS NOT NULL)))),
    CONSTRAINT booking_confirmation_receipts_check1 CHECK ((((accepted_review_id IS NULL) AND (accepted_review_version IS NULL)) OR ((accepted_review_id IS NOT NULL) AND (accepted_review_version IS NOT NULL) AND (accepted_review_version >= 1)))),
    CONSTRAINT booking_confirmation_receipts_extraction_sha256_check CHECK (((extraction_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT booking_confirmation_receipts_original_booking_index_check CHECK ((original_booking_index >= 0)),
    CONSTRAINT booking_confirmation_receipts_reservation_id_check CHECK ((reservation_id > 0)),
    CONSTRAINT booking_confirmation_receipts_schema_version_check CHECK ((schema_version = ANY (ARRAY[1, 2, 3])))
);

--
-- Name: booking_confirmation_receipts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.booking_confirmation_receipts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.booking_confirmation_receipts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: destinations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.destinations (
    id bigint NOT NULL,
    arrival_date date NOT NULL,
    country character varying(255) NOT NULL,
    departure_date date,
    name character varying(255) NOT NULL,
    timezone character varying(255),
    trip_id bigint NOT NULL
);

--
-- Name: destinations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.destinations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.destinations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: document_processing_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_processing_outbox (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    document_id bigint NOT NULL,
    published_at timestamp(6) with time zone,
    status character varying(255) NOT NULL,
    processing_job_id uuid,
    processing_failed_at timestamp with time zone,
    extraction_contract character varying(32),
    CONSTRAINT document_processing_contract_job_required CHECK (((extraction_contract IS NULL) OR (processing_job_id IS NOT NULL))),
    CONSTRAINT document_processing_extraction_contract_valid CHECK (((extraction_contract IS NULL) OR ((extraction_contract)::text = ANY ((ARRAY['LEGACY'::character varying, 'SEMANTIC_V2'::character varying, 'SEMANTIC_V3_4'::character varying, 'AUTO_V3_4'::character varying, 'FLIGHT_V1'::character varying, 'UNSUPPORTED'::character varying, 'SOURCE_BOOKING_V1_REVIEW'::character varying])::text[])))),
    CONSTRAINT document_processing_outbox_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHED'::character varying])::text[])))
);

--
-- Name: document_processing_outbox_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.document_processing_outbox ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_processing_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: explore_personal_hours; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_personal_hours (
    trip_id bigint NOT NULL,
    user_id bigint NOT NULL,
    preferences jsonb NOT NULL,
    version bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT explore_personal_hours_preferences_check CHECK ((jsonb_typeof(preferences) = 'object'::text)),
    CONSTRAINT explore_personal_hours_version_check CHECK ((version > 0))
);

--
-- Name: explore_plan_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_plan_applications (
    trip_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    request_id uuid NOT NULL,
    request_sha256 character varying(64) NOT NULL,
    itinerary_item_ids text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT explore_plan_applications_itinerary_item_ids_check CHECK ((jsonb_typeof((itinerary_item_ids)::jsonb) = 'array'::text)),
    CONSTRAINT explore_plan_applications_request_sha256_check CHECK (((request_sha256)::text ~ '^[a-f0-9]{64}$'::text))
);

--
-- Name: explore_provider_usage_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_provider_usage_operations (
    id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    operation_key character varying(96) NOT NULL,
    operation_kind character varying(32) NOT NULL,
    sku character varying(96) NOT NULL,
    price_book_version character varying(64) NOT NULL,
    billable_units integer NOT NULL,
    unit_price_micros bigint NOT NULL,
    estimated_cost_micros bigint GENERATED ALWAYS AS (((billable_units)::bigint * unit_price_micros)) STORED,
    state character varying(24) DEFAULT 'RESERVED'::character varying NOT NULL,
    dispatch_fence uuid,
    authorized_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_explore_usage_operation_cost CHECK (((billable_units > 0) AND (unit_price_micros >= 0))),
    CONSTRAINT ck_explore_usage_operation_identity CHECK (((length((operation_key)::text) > 0) AND (length((sku)::text) > 0) AND (length((price_book_version)::text) > 0))),
    CONSTRAINT ck_explore_usage_operation_kind CHECK (((operation_kind)::text = ANY ((ARRAY['PLACES_NEARBY'::character varying, 'PLACES_TEXT'::character varying, 'PLACES_DETAILS'::character varying, 'ROUTES_COMPUTE'::character varying, 'ROUTES_MATRIX'::character varying])::text[]))),
    CONSTRAINT ck_explore_usage_operation_state CHECK (((((state)::text = 'RESERVED'::text) AND (dispatch_fence IS NULL) AND (authorized_at IS NULL) AND (completed_at IS NULL)) OR (((state)::text = 'RELEASED'::text) AND (dispatch_fence IS NULL) AND (authorized_at IS NULL) AND (completed_at IS NOT NULL)) OR (((state)::text = 'DISPATCH_AUTHORIZED'::text) AND (dispatch_fence IS NOT NULL) AND (authorized_at IS NOT NULL) AND (completed_at IS NULL)) OR (((state)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying, 'UNKNOWN'::character varying])::text[])) AND (dispatch_fence IS NOT NULL) AND (authorized_at IS NOT NULL) AND (completed_at IS NOT NULL)))),
    CONSTRAINT ck_explore_usage_operation_times CHECK ((((authorized_at IS NULL) OR (authorized_at >= created_at)) AND ((completed_at IS NULL) OR (completed_at >= COALESCE(authorized_at, created_at)))))
);

--
-- Name: explore_provider_usage_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_provider_usage_policies (
    budget_scope character varying(96) NOT NULL,
    active_revision_id uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_explore_usage_policy_scope CHECK ((length((budget_scope)::text) > 0))
);

--
-- Name: explore_provider_usage_policy_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_provider_usage_policy_revisions (
    id uuid NOT NULL,
    budget_scope character varying(96) NOT NULL,
    previous_revision_id uuid,
    protocol_version integer NOT NULL,
    price_book_version character varying(64) NOT NULL,
    price_book_digest character(64) NOT NULL,
    hourly_limit_micros bigint NOT NULL,
    daily_limit_micros bigint NOT NULL,
    monthly_limit_micros bigint NOT NULL,
    admissions_enabled boolean NOT NULL,
    effective_at timestamp with time zone NOT NULL,
    admission_valid_until timestamp with time zone NOT NULL,
    approved_by character varying(160) NOT NULL,
    approval_reference character varying(160) NOT NULL,
    reason character varying(500) NOT NULL,
    window_changes jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    accounting_mode character varying(24) DEFAULT 'OPTIONAL_HARD_CAP'::character varying NOT NULL,
    CONSTRAINT ck_explore_usage_revision_approval CHECK (((length((approved_by)::text) > 0) AND (length((approval_reference)::text) > 0) AND (length((reason)::text) > 0))),
    CONSTRAINT ck_explore_usage_revision_configuration CHECK (((protocol_version > 0) AND (length((price_book_version)::text) > 0) AND (price_book_digest ~ '^[0-9a-f]{64}$'::text) AND ((((accounting_mode)::text = 'TRACK_ONLY'::text) AND (hourly_limit_micros = 0) AND (daily_limit_micros = 0) AND (monthly_limit_micros = 0)) OR (((accounting_mode)::text = 'OPTIONAL_HARD_CAP'::text) AND (hourly_limit_micros > 0) AND (daily_limit_micros > 0) AND (monthly_limit_micros > 0))))),
    CONSTRAINT ck_explore_usage_revision_interval CHECK ((admission_valid_until > effective_at)),
    CONSTRAINT ck_explore_usage_revision_window_changes CHECK ((jsonb_typeof(window_changes) = 'array'::text))
);

--
-- Name: explore_provider_usage_reservations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_provider_usage_reservations (
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    phase_key character varying(96) NOT NULL,
    policy_revision_id uuid NOT NULL,
    session_id uuid,
    operation_plan_digest character(64) NOT NULL,
    hourly_window_id bigint NOT NULL,
    daily_window_id bigint NOT NULL,
    monthly_window_id bigint NOT NULL,
    state character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    settled_at timestamp with time zone,
    CONSTRAINT ck_explore_usage_distinct_windows CHECK (((hourly_window_id <> daily_window_id) AND (hourly_window_id <> monthly_window_id) AND (daily_window_id <> monthly_window_id))),
    CONSTRAINT ck_explore_usage_phase_key CHECK ((length((phase_key)::text) > 0)),
    CONSTRAINT ck_explore_usage_plan_digest CHECK ((operation_plan_digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_explore_usage_reservation_interval CHECK ((expires_at > created_at)),
    CONSTRAINT ck_explore_usage_reservation_state CHECK (((((state)::text = 'OPEN'::text) AND (settled_at IS NULL)) OR (((state)::text = ANY ((ARRAY['CLOSED'::character varying, 'ABANDONED'::character varying])::text[])) AND (settled_at IS NOT NULL))))
);

--
-- Name: explore_provider_usage_windows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explore_provider_usage_windows (
    id bigint NOT NULL,
    policy_key character varying(64) NOT NULL,
    budget_scope character varying(96) NOT NULL,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone NOT NULL,
    currency character(3) DEFAULT 'USD'::bpchar NOT NULL,
    applied_policy_revision_id uuid NOT NULL,
    limit_micros bigint NOT NULL,
    reserved_micros bigint DEFAULT 0 NOT NULL,
    charged_micros bigint DEFAULT 0 NOT NULL,
    uncertainty_hold_micros bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_explore_usage_window_amounts CHECK (((limit_micros >= 0) AND (reserved_micros >= 0) AND (charged_micros >= 0) AND (uncertainty_hold_micros >= 0))),
    CONSTRAINT ck_explore_usage_window_configuration CHECK ((length((budget_scope)::text) > 0)),
    CONSTRAINT ck_explore_usage_window_currency CHECK ((currency = 'USD'::bpchar)),
    CONSTRAINT ck_explore_usage_window_interval CHECK ((ends_at > starts_at)),
    CONSTRAINT ck_explore_usage_window_policy CHECK (((policy_key)::text = ANY ((ARRAY['GOOGLE_UTC_HOUR'::character varying, 'GOOGLE_UTC_DAY'::character varying, 'GOOGLE_UTC_MONTH'::character varying])::text[])))
);

--
-- Name: explore_provider_usage_windows_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.explore_provider_usage_windows ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.explore_provider_usage_windows_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: flight_segments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flight_segments (
    id bigint NOT NULL,
    arrival_airport_code character varying(255) NOT NULL,
    arrival_date_time timestamp(6) without time zone NOT NULL,
    departure_airport_code character varying(255) NOT NULL,
    departure_date_time timestamp(6) without time zone NOT NULL,
    departure_time_zone character varying(255),
    flight_number character varying(255) NOT NULL,
    segment_order integer NOT NULL,
    reservation_id bigint NOT NULL
);

--
-- Name: flight_segments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.flight_segments ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.flight_segments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: itineraries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.itineraries (
    id bigint NOT NULL,
    trip_id bigint NOT NULL
);

--
-- Name: itineraries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.itineraries ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.itineraries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: itinerary_conflicts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.itinerary_conflicts (
    id bigint NOT NULL,
    status character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    first_item_id bigint NOT NULL,
    second_item_id bigint NOT NULL,
    trip_member_id bigint NOT NULL,
    CONSTRAINT itinerary_conflicts_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying, 'DISMISSED'::character varying])::text[]))),
    CONSTRAINT itinerary_conflicts_type_check CHECK (((type)::text = 'MEMBER_OVERLAP'::text))
);

--
-- Name: itinerary_conflicts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.itinerary_conflicts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.itinerary_conflicts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: itinerary_item_participants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.itinerary_item_participants (
    id bigint NOT NULL,
    itinerary_item_id bigint NOT NULL,
    trip_member_id bigint NOT NULL
);

--
-- Name: itinerary_item_participants_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.itinerary_item_participants ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.itinerary_item_participants_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: itinerary_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.itinerary_items (
    id bigint NOT NULL,
    end_date_time timestamp(6) without time zone,
    flexibility character varying(255) NOT NULL,
    start_date_time timestamp(6) without time zone NOT NULL,
    title character varying(255) NOT NULL,
    activity_id bigint,
    itinerary_id bigint NOT NULL,
    reservation_id bigint,
    CONSTRAINT itinerary_items_flexibility_check CHECK (((flexibility)::text = ANY ((ARRAY['FLEXIBLE'::character varying, 'TIME_CONSTRAINED'::character varying, 'FIXED'::character varying])::text[])))
);

--
-- Name: itinerary_items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.itinerary_items ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.itinerary_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: reservations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reservations (
    id bigint NOT NULL,
    confirmation_number character varying(255),
    confirmation_number_type character varying(255),
    end_date_time timestamp(6) without time zone,
    location character varying(255),
    name character varying(255) NOT NULL,
    provider character varying(255),
    start_date_time timestamp(6) without time zone NOT NULL,
    status character varying(255),
    type character varying(255) NOT NULL,
    trip_id bigint NOT NULL,
    location_address character varying(255),
    location_latitude numeric(10,7),
    location_longitude numeric(10,7),
    location_name character varying(255),
    schedule_time_zone character varying(100),
    confirmed_extraction_id bigint,
    confirmed_booking_index integer,
    required_arrival_at timestamp with time zone,
    arrival_reviewed_at timestamp with time zone,
    ending_location_name character varying(255),
    ending_location_address character varying(1000),
    ending_location_latitude numeric(10,7),
    ending_location_longitude numeric(10,7),
    ending_place_id character varying(255),
    ending_location_source character varying(255),
    ending_location_reviewed_at timestamp with time zone,
    ending_location_version bigint DEFAULT 0 NOT NULL,
    correction_review_id bigint,
    endpoint_clarification_actor_id bigint,
    endpoint_clarification_at timestamp with time zone,
    endpoint_clarification_choice character varying(255),
    endpoint_clarification_name character varying(255),
    endpoint_clarification_address character varying(1000),
    endpoint_update_id bigint,
    CONSTRAINT activity_endpoint_coordinate_pair CHECK (((ending_location_latitude IS NULL) = (ending_location_longitude IS NULL))),
    CONSTRAINT activity_endpoint_coordinates CHECK ((((ending_location_latitude >= ('-90'::integer)::numeric) AND (ending_location_latitude <= (90)::numeric)) AND ((ending_location_longitude >= ('-180'::integer)::numeric) AND (ending_location_longitude <= (180)::numeric)))),
    CONSTRAINT activity_endpoint_google_id_only CHECK (COALESCE((((ending_place_id IS NULL) AND ((ending_location_source)::text IS DISTINCT FROM 'USER_CONFIRMED_PLACE_ID'::text)) OR ((ending_place_id IS NOT NULL) AND ((ending_place_id)::text ~ '^[A-Za-z0-9_-]{1,255}$'::text) AND ((((ending_location_source)::text = 'USER_CONFIRMED_PLACE_ID'::text) AND (ending_location_name IS NULL) AND (ending_location_address IS NULL) AND (ending_location_latitude IS NULL) AND (ending_location_longitude IS NULL)) OR (((ending_location_source)::text = 'USER_CONFIRMED_SAME_AS_MEETING'::text) AND ((NULLIF(btrim((ending_location_name)::text), ''::text) IS NOT NULL) OR (NULLIF(btrim((ending_location_address)::text), ''::text) IS NOT NULL)) AND (NOT ((ending_location_name)::text IS DISTINCT FROM (location_name)::text)) AND (NOT ((ending_location_address)::text IS DISTINCT FROM (location_address)::text)) AND (NOT (ending_location_latitude IS DISTINCT FROM location_latitude)) AND (NOT (ending_location_longitude IS DISTINCT FROM location_longitude)))))), false)),
    CONSTRAINT activity_endpoint_review CHECK ((((ending_location_source IS NULL) AND (ending_location_reviewed_at IS NULL) AND (ending_location_version = 0)) OR (((ending_location_source)::text = ANY ((ARRAY['UNKNOWN'::character varying, 'USER_CONFIRMED'::character varying, 'USER_CONFIRMED_SAME_AS_MEETING'::character varying, 'USER_CONFIRMED_PLACE_ID'::character varying])::text[])) AND (ending_location_reviewed_at IS NOT NULL) AND (ending_location_version > 0)))),
    CONSTRAINT activity_endpoint_version CHECK ((ending_location_version >= 0)),
    CONSTRAINT ck_reservation_confirmed_booking CHECK ((((confirmed_extraction_id IS NULL) AND (confirmed_booking_index IS NULL)) OR ((confirmed_extraction_id IS NOT NULL) AND (confirmed_booking_index IS NOT NULL) AND (confirmed_booking_index >= 0)))),
    CONSTRAINT initial_endpoint_clarification CHECK ((((endpoint_clarification_actor_id IS NULL) AND (endpoint_clarification_at IS NULL) AND (endpoint_clarification_choice IS NULL) AND (endpoint_clarification_name IS NULL) AND (endpoint_clarification_address IS NULL)) OR ((endpoint_clarification_actor_id IS NOT NULL) AND (endpoint_clarification_at IS NOT NULL) AND (endpoint_clarification_choice IS NOT NULL) AND ((endpoint_clarification_choice)::text = ANY ((ARRAY['UNKNOWN'::character varying, 'SAME_AS_MEETING_POINT'::character varying, 'LOCATION'::character varying])::text[])) AND (confirmed_extraction_id IS NOT NULL)))),
    CONSTRAINT reservation_reviewed_arrival_pair CHECK (((required_arrival_at IS NULL) = (arrival_reviewed_at IS NULL))),
    CONSTRAINT reservations_confirmation_number_type_check CHECK (((confirmation_number_type)::text = ANY ((ARRAY['PNR'::character varying, 'BOOKING_ID'::character varying])::text[]))),
    CONSTRAINT reservations_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CONFIRMED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT reservations_type_check CHECK (((type)::text = ANY ((ARRAY['FLIGHT'::character varying, 'HOTEL'::character varying, 'TRAIN'::character varying, 'RESTAURANT'::character varying, 'ACTIVITY'::character varying, 'CAR_RENTAL'::character varying, 'OTHER'::character varying])::text[])))
);

--
-- Name: reservations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.reservations ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.reservations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: tour_correction_reviews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tour_correction_reviews (
    id bigint NOT NULL,
    document_id bigint NOT NULL,
    extraction_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    request_id uuid NOT NULL,
    extraction_sha256 character varying(64) NOT NULL,
    document_sha256 character varying(64) NOT NULL,
    changes_json text NOT NULL,
    assessment_json text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    accepted_at timestamp with time zone,
    accepted_by bigint,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT tour_correction_reviews_assessment_json_check CHECK ((jsonb_typeof((assessment_json)::jsonb) = 'object'::text)),
    CONSTRAINT tour_correction_reviews_changes_json_check CHECK ((jsonb_typeof((changes_json)::jsonb) = 'array'::text)),
    CONSTRAINT tour_correction_reviews_check CHECK ((((accepted_at IS NULL) AND (accepted_by IS NULL) AND (version = 0)) OR ((accepted_at IS NOT NULL) AND (accepted_by IS NOT NULL) AND (accepted_by = actor_id) AND (version = 1)))),
    CONSTRAINT tour_correction_reviews_document_sha256_check CHECK (((document_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT tour_correction_reviews_extraction_sha256_check CHECK (((extraction_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT tour_correction_reviews_version_check CHECK ((version >= 0))
);

--
-- Name: tour_correction_reviews_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.tour_correction_reviews ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.tour_correction_reviews_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: travel_document_extractions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.travel_document_extractions (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    extracted_data character varying(100000) NOT NULL,
    travel_document_id bigint NOT NULL
);

--
-- Name: travel_document_extractions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.travel_document_extractions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.travel_document_extractions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: travel_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.travel_documents (
    id bigint NOT NULL,
    content_type character varying(255) NOT NULL,
    document_type character varying(255),
    original_file_name character varying(255) NOT NULL,
    processing_attempt_id uuid,
    processing_started_at timestamp(6) with time zone,
    processing_status character varying(255) NOT NULL,
    storage_key character varying(255) NOT NULL,
    uploaded_at timestamp(6) with time zone NOT NULL,
    trip_id bigint NOT NULL,
    uploaded_by_user_id bigint,
    processing_job_id uuid,
    processing_attempt_count integer DEFAULT 0 NOT NULL,
    processing_delivery_count integer DEFAULT 0 NOT NULL,
    processing_failure_count integer DEFAULT 0 NOT NULL,
    processing_invalid_output_count integer DEFAULT 0 NOT NULL,
    processing_last_error_code character varying(255),
    processing_failed_at timestamp with time zone,
    processing_extraction_timeout_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT document_extraction_timeouts_valid CHECK (((processing_extraction_timeout_count >= 0) AND (processing_extraction_timeout_count <= processing_failure_count))),
    CONSTRAINT document_processing_counts_valid CHECK (((processing_attempt_count >= 0) AND (processing_delivery_count >= 0) AND (processing_failure_count >= 0) AND (processing_invalid_output_count >= 0) AND (processing_invalid_output_count <= processing_failure_count) AND (processing_failure_count <= processing_attempt_count))),
    CONSTRAINT document_processing_error_valid CHECK (((processing_last_error_code IS NULL) OR ((processing_last_error_code)::text = ANY ((ARRAY['INVALID_EXTRACTION'::character varying, 'INVALID_SOURCE'::character varying, 'TECHNICAL_FAILURE'::character varying, 'RETRIES_EXHAUSTED'::character varying, 'EXTRACTION_TIMEOUT'::character varying])::text[])))),
    CONSTRAINT travel_documents_document_type_check CHECK (((document_type)::text = ANY ((ARRAY['FLIGHT_CONFIRMATION'::character varying, 'HOTEL_BOOKING'::character varying, 'TRAIN_TICKET'::character varying, 'ATTRACTION_TICKET'::character varying, 'TOUR_BOOKING'::character varying, 'RESTAURANT_RESERVATION'::character varying])::text[]))),
    CONSTRAINT travel_documents_processing_status_check CHECK (((processing_status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'REVIEW_REQUIRED'::character varying, 'PROCESSED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: travel_documents_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.travel_documents ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.travel_documents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: trip; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trip (
    id bigint NOT NULL,
    end_date date NOT NULL,
    name character varying(255) NOT NULL,
    start_date date NOT NULL,
    status character varying(255),
    trip_type character varying(255) NOT NULL,
    CONSTRAINT trip_status_check CHECK (((status)::text = ANY ((ARRAY['PLANNING'::character varying, 'ACTIVE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT trip_trip_type_check CHECK (((trip_type)::text = ANY ((ARRAY['SOLO'::character varying, 'GROUP'::character varying])::text[])))
);

--
-- Name: trip_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.trip ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.trip_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: trip_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trip_members (
    id bigint NOT NULL,
    role character varying(255) NOT NULL,
    trip_id bigint NOT NULL,
    user_id bigint NOT NULL,
    CONSTRAINT trip_members_role_check CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'EDITOR'::character varying, 'VIEWER'::character varying])::text[])))
);

--
-- Name: trip_members_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.trip_members ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.trip_members_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: user_auth_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_auth_identities (
    id bigint NOT NULL,
    issuer character varying(512) NOT NULL,
    subject character varying(255) NOT NULL,
    user_id bigint NOT NULL,
    linked_at timestamp with time zone NOT NULL,
    CONSTRAINT user_auth_identity_nonblank CHECK (((btrim((issuer)::text) <> ''::text) AND (btrim((subject)::text) <> ''::text)))
);

--
-- Name: user_auth_identities_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.user_auth_identities ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_auth_identities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255) NOT NULL
);

--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: activities activities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activities
    ADD CONSTRAINT activities_pkey PRIMARY KEY (id);

--
-- Name: activity_endpoint_history activity_endpoint_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_endpoint_history
    ADD CONSTRAINT activity_endpoint_history_pkey PRIMARY KEY (id);

--
-- Name: activity_endpoint_history activity_endpoint_history_reservation_id_endpoint_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_endpoint_history
    ADD CONSTRAINT activity_endpoint_history_reservation_id_endpoint_version_key UNIQUE (reservation_id, endpoint_version);

--
-- Name: ai_conversation_turns ai_conversation_turns_conversation_id_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversation_turns
    ADD CONSTRAINT ai_conversation_turns_conversation_id_request_id_key UNIQUE (conversation_id, request_id);

--
-- Name: ai_conversation_turns ai_conversation_turns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversation_turns
    ADD CONSTRAINT ai_conversation_turns_pkey PRIMARY KEY (id);

--
-- Name: ai_conversations ai_conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversations
    ADD CONSTRAINT ai_conversations_pkey PRIMARY KEY (id);

--
-- Name: ai_messages ai_messages_conversation_id_sequence_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_messages
    ADD CONSTRAINT ai_messages_conversation_id_sequence_number_key UNIQUE (conversation_id, sequence_number);

--
-- Name: ai_messages ai_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_messages
    ADD CONSTRAINT ai_messages_pkey PRIMARY KEY (id);

--
-- Name: booking_confirmation_receipts booking_confirmation_receipts_extraction_id_original_bookin_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.booking_confirmation_receipts
    ADD CONSTRAINT booking_confirmation_receipts_extraction_id_original_bookin_key UNIQUE (extraction_id, original_booking_index);

--
-- Name: booking_confirmation_receipts booking_confirmation_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.booking_confirmation_receipts
    ADD CONSTRAINT booking_confirmation_receipts_pkey PRIMARY KEY (id);

--
-- Name: destinations destinations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.destinations
    ADD CONSTRAINT destinations_pkey PRIMARY KEY (id);

--
-- Name: document_processing_outbox document_processing_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_processing_outbox
    ADD CONSTRAINT document_processing_outbox_pkey PRIMARY KEY (id);

--
-- Name: explore_personal_hours explore_personal_hours_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_personal_hours
    ADD CONSTRAINT explore_personal_hours_pkey PRIMARY KEY (trip_id, user_id);

--
-- Name: explore_plan_applications explore_plan_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_plan_applications
    ADD CONSTRAINT explore_plan_applications_pkey PRIMARY KEY (trip_id, actor_id, request_id);

--
-- Name: explore_provider_usage_operations explore_provider_usage_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_operations
    ADD CONSTRAINT explore_provider_usage_operations_pkey PRIMARY KEY (id);

--
-- Name: explore_provider_usage_policies explore_provider_usage_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_policies
    ADD CONSTRAINT explore_provider_usage_policies_pkey PRIMARY KEY (budget_scope);

--
-- Name: explore_provider_usage_policy_revisions explore_provider_usage_policy_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_policy_revisions
    ADD CONSTRAINT explore_provider_usage_policy_revisions_pkey PRIMARY KEY (id);

--
-- Name: explore_provider_usage_reservations explore_provider_usage_reservations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_reservations
    ADD CONSTRAINT explore_provider_usage_reservations_pkey PRIMARY KEY (id);

--
-- Name: explore_provider_usage_windows explore_provider_usage_windows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_windows
    ADD CONSTRAINT explore_provider_usage_windows_pkey PRIMARY KEY (id);

--
-- Name: flight_segments flight_segments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flight_segments
    ADD CONSTRAINT flight_segments_pkey PRIMARY KEY (id);

--
-- Name: itineraries itineraries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itineraries
    ADD CONSTRAINT itineraries_pkey PRIMARY KEY (id);

--
-- Name: itinerary_conflicts itinerary_conflicts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_conflicts
    ADD CONSTRAINT itinerary_conflicts_pkey PRIMARY KEY (id);

--
-- Name: itinerary_item_participants itinerary_item_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_item_participants
    ADD CONSTRAINT itinerary_item_participants_pkey PRIMARY KEY (id);

--
-- Name: itinerary_items itinerary_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_items
    ADD CONSTRAINT itinerary_items_pkey PRIMARY KEY (id);

--
-- Name: reservations reservations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_pkey PRIMARY KEY (id);

--
-- Name: tour_correction_reviews tour_correction_reviews_document_id_actor_id_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tour_correction_reviews
    ADD CONSTRAINT tour_correction_reviews_document_id_actor_id_request_id_key UNIQUE (document_id, actor_id, request_id);

--
-- Name: tour_correction_reviews tour_correction_reviews_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tour_correction_reviews
    ADD CONSTRAINT tour_correction_reviews_pkey PRIMARY KEY (id);

--
-- Name: travel_document_extractions travel_document_extractions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_document_extractions
    ADD CONSTRAINT travel_document_extractions_pkey PRIMARY KEY (id);

--
-- Name: travel_documents travel_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_documents
    ADD CONSTRAINT travel_documents_pkey PRIMARY KEY (id);

--
-- Name: trip_members trip_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trip_members
    ADD CONSTRAINT trip_members_pkey PRIMARY KEY (id);

--
-- Name: trip trip_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trip
    ADD CONSTRAINT trip_pkey PRIMARY KEY (id);

--
-- Name: itinerary_item_participants uk1ko45lml1jsdv7g4b5kvgvvl6; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_item_participants
    ADD CONSTRAINT uk1ko45lml1jsdv7g4b5kvgvvl6 UNIQUE (itinerary_item_id, trip_member_id);

--
-- Name: flight_segments uk_flight_segment_reservation_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flight_segments
    ADD CONSTRAINT uk_flight_segment_reservation_order UNIQUE (reservation_id, segment_order);

--
-- Name: itineraries ukc73fljqq86fxrcj7akme5rrfc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itineraries
    ADD CONSTRAINT ukc73fljqq86fxrcj7akme5rrfc UNIQUE (trip_id);

--
-- Name: trip_members ukhg97io4ecao95cff2peffr9u9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trip_members
    ADD CONSTRAINT ukhg97io4ecao95cff2peffr9u9 UNIQUE (trip_id, user_id);

--
-- Name: explore_provider_usage_operations uq_explore_usage_operation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_operations
    ADD CONSTRAINT uq_explore_usage_operation UNIQUE (reservation_id, operation_key);

--
-- Name: explore_provider_usage_reservations uq_explore_usage_phase; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_reservations
    ADD CONSTRAINT uq_explore_usage_phase UNIQUE (request_id, phase_key);

--
-- Name: explore_provider_usage_policy_revisions uq_explore_usage_revision_scope; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_policy_revisions
    ADD CONSTRAINT uq_explore_usage_revision_scope UNIQUE (budget_scope, id);

--
-- Name: explore_provider_usage_windows uq_explore_usage_window; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_windows
    ADD CONSTRAINT uq_explore_usage_window UNIQUE (policy_key, budget_scope, starts_at);

--
-- Name: user_auth_identities uq_user_auth_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_auth_identities
    ADD CONSTRAINT uq_user_auth_identity UNIQUE (issuer, subject);

--
-- Name: user_auth_identities user_auth_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_auth_identities
    ADD CONSTRAINT user_auth_identities_pkey PRIMARY KEY (id);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: ai_conversation_one_running_turn; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ai_conversation_one_running_turn ON public.ai_conversation_turns USING btree (conversation_id) WHERE ((status)::text = 'RUNNING'::text);

--
-- Name: ai_conversation_owner_trip_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ai_conversation_owner_trip_updated ON public.ai_conversations USING btree (owner_id, trip_id, updated_at DESC, id DESC);

--
-- Name: document_processing_outbox_job_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX document_processing_outbox_job_unique ON public.document_processing_outbox USING btree (processing_job_id) WHERE (processing_job_id IS NOT NULL);

--
-- Name: ix_document_trip_uploader; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_document_trip_uploader ON public.travel_documents USING btree (trip_id, uploaded_by_user_id);

--
-- Name: ix_explore_usage_open_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_explore_usage_open_expiry ON public.explore_provider_usage_reservations USING btree (expires_at) WHERE ((state)::text = 'OPEN'::text);

--
-- Name: ix_explore_usage_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_explore_usage_session ON public.explore_provider_usage_reservations USING btree (session_id, created_at) WHERE (session_id IS NOT NULL);

--
-- Name: ix_explore_usage_unfinished_operations; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_explore_usage_unfinished_operations ON public.explore_provider_usage_operations USING btree (reservation_id, state) WHERE ((state)::text = ANY ((ARRAY['RESERVED'::character varying, 'DISPATCH_AUTHORIZED'::character varying])::text[]));

--
-- Name: user_auth_identities_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_auth_identities_user_idx ON public.user_auth_identities USING btree (user_id);

--
-- Name: activity_endpoint_history activity_endpoint_history_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER activity_endpoint_history_immutable BEFORE INSERT OR DELETE OR UPDATE ON public.activity_endpoint_history FOR EACH ROW EXECUTE FUNCTION public.protect_activity_endpoint_history();

--
-- Name: booking_confirmation_receipts booking_confirmation_receipt_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER booking_confirmation_receipt_immutable BEFORE INSERT OR DELETE OR UPDATE ON public.booking_confirmation_receipts FOR EACH ROW EXECUTE FUNCTION public.protect_booking_confirmation_receipt();

--
-- Name: reservations current_endpoint_history; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER current_endpoint_history BEFORE INSERT OR UPDATE ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.protect_current_endpoint_history();

--
-- Name: document_processing_outbox document_processing_contract_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER document_processing_contract_immutable BEFORE UPDATE OF extraction_contract ON public.document_processing_outbox FOR EACH ROW EXECUTE FUNCTION public.prevent_document_processing_contract_change();

--
-- Name: travel_documents document_uploader_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER document_uploader_immutable BEFORE UPDATE ON public.travel_documents FOR EACH ROW EXECUTE FUNCTION public.protect_document_uploader();

--
-- Name: explore_provider_usage_operations explore_usage_operation_snapshot_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER explore_usage_operation_snapshot_immutable BEFORE UPDATE ON public.explore_provider_usage_operations FOR EACH ROW EXECUTE FUNCTION public.protect_explore_usage_operation_snapshot();

--
-- Name: explore_provider_usage_policy_revisions explore_usage_policy_revision_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER explore_usage_policy_revision_immutable BEFORE DELETE OR UPDATE ON public.explore_provider_usage_policy_revisions FOR EACH ROW EXECUTE FUNCTION public.protect_explore_usage_policy_revision();

--
-- Name: reservations initial_endpoint_clarification_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER initial_endpoint_clarification_immutable BEFORE INSERT OR UPDATE ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.protect_initial_endpoint_clarification();

--
-- Name: travel_document_extractions original_extraction_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER original_extraction_immutable BEFORE DELETE OR UPDATE ON public.travel_document_extractions FOR EACH ROW EXECUTE FUNCTION public.protect_original_tour_extraction();

--
-- Name: reservations reservation_confirmed_schedule_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reservation_confirmed_schedule_immutable BEFORE UPDATE ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.protect_reservation_confirmed_schedule();

--
-- Name: reservations reservation_correction_link; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER reservation_correction_link BEFORE INSERT OR UPDATE ON public.reservations FOR EACH ROW EXECUTE FUNCTION public.protect_tour_correction_link();

--
-- Name: tour_correction_reviews tour_correction_history_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER tour_correction_history_immutable BEFORE DELETE OR UPDATE ON public.tour_correction_reviews FOR EACH ROW EXECUTE FUNCTION public.protect_tour_correction_history();

--
-- Name: user_auth_identities user_auth_identity_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER user_auth_identity_immutable BEFORE UPDATE ON public.user_auth_identities FOR EACH ROW EXECUTE FUNCTION public.protect_user_auth_identity();

--
-- Name: activity_endpoint_history activity_endpoint_history_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_endpoint_history
    ADD CONSTRAINT activity_endpoint_history_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id);

--
-- Name: activity_endpoint_history activity_endpoint_history_extraction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_endpoint_history
    ADD CONSTRAINT activity_endpoint_history_extraction_id_fkey FOREIGN KEY (extraction_id) REFERENCES public.travel_document_extractions(id);

--
-- Name: activity_endpoint_history activity_endpoint_history_live_reservation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_endpoint_history
    ADD CONSTRAINT activity_endpoint_history_live_reservation_id_fkey FOREIGN KEY (live_reservation_id) REFERENCES public.reservations(id) ON DELETE SET NULL;

--
-- Name: activity_endpoint_history activity_endpoint_history_trip_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_endpoint_history
    ADD CONSTRAINT activity_endpoint_history_trip_id_fkey FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: ai_conversation_turns ai_conversation_turns_conversation_id_assistant_sequence_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversation_turns
    ADD CONSTRAINT ai_conversation_turns_conversation_id_assistant_sequence_fkey FOREIGN KEY (conversation_id, assistant_sequence) REFERENCES public.ai_messages(conversation_id, sequence_number) ON DELETE CASCADE;

--
-- Name: ai_conversation_turns ai_conversation_turns_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversation_turns
    ADD CONSTRAINT ai_conversation_turns_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.ai_conversations(id) ON DELETE CASCADE;

--
-- Name: ai_conversation_turns ai_conversation_turns_conversation_id_user_sequence_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversation_turns
    ADD CONSTRAINT ai_conversation_turns_conversation_id_user_sequence_fkey FOREIGN KEY (conversation_id, user_sequence) REFERENCES public.ai_messages(conversation_id, sequence_number) ON DELETE CASCADE;

--
-- Name: ai_conversations ai_conversations_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversations
    ADD CONSTRAINT ai_conversations_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(id) ON DELETE CASCADE;

--
-- Name: ai_conversations ai_conversations_trip_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_conversations
    ADD CONSTRAINT ai_conversations_trip_id_fkey FOREIGN KEY (trip_id) REFERENCES public.trip(id) ON DELETE SET NULL;

--
-- Name: ai_messages ai_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_messages
    ADD CONSTRAINT ai_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.ai_conversations(id) ON DELETE CASCADE;

--
-- Name: booking_confirmation_receipts booking_confirmation_receipts_accepted_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.booking_confirmation_receipts
    ADD CONSTRAINT booking_confirmation_receipts_accepted_review_id_fkey FOREIGN KEY (accepted_review_id) REFERENCES public.tour_correction_reviews(id);

--
-- Name: booking_confirmation_receipts booking_confirmation_receipts_confirmed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.booking_confirmation_receipts
    ADD CONSTRAINT booking_confirmation_receipts_confirmed_by_fkey FOREIGN KEY (confirmed_by) REFERENCES public.users(id);

--
-- Name: booking_confirmation_receipts booking_confirmation_receipts_extraction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.booking_confirmation_receipts
    ADD CONSTRAINT booking_confirmation_receipts_extraction_id_fkey FOREIGN KEY (extraction_id) REFERENCES public.travel_document_extractions(id) ON DELETE CASCADE;

--
-- Name: explore_personal_hours explore_personal_hours_trip_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_personal_hours
    ADD CONSTRAINT explore_personal_hours_trip_id_fkey FOREIGN KEY (trip_id) REFERENCES public.trip(id) ON DELETE CASCADE;

--
-- Name: explore_personal_hours explore_personal_hours_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_personal_hours
    ADD CONSTRAINT explore_personal_hours_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

--
-- Name: explore_plan_applications explore_plan_applications_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_plan_applications
    ADD CONSTRAINT explore_plan_applications_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id);

--
-- Name: explore_plan_applications explore_plan_applications_trip_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_plan_applications
    ADD CONSTRAINT explore_plan_applications_trip_id_fkey FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: explore_provider_usage_operations explore_provider_usage_operations_reservation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_operations
    ADD CONSTRAINT explore_provider_usage_operations_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.explore_provider_usage_reservations(id);

--
-- Name: explore_provider_usage_policy_revisions explore_provider_usage_policy_revisions_budget_scope_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_policy_revisions
    ADD CONSTRAINT explore_provider_usage_policy_revisions_budget_scope_fkey FOREIGN KEY (budget_scope) REFERENCES public.explore_provider_usage_policies(budget_scope);

--
-- Name: explore_provider_usage_reservations explore_provider_usage_reservations_daily_window_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_reservations
    ADD CONSTRAINT explore_provider_usage_reservations_daily_window_id_fkey FOREIGN KEY (daily_window_id) REFERENCES public.explore_provider_usage_windows(id);

--
-- Name: explore_provider_usage_reservations explore_provider_usage_reservations_hourly_window_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_reservations
    ADD CONSTRAINT explore_provider_usage_reservations_hourly_window_id_fkey FOREIGN KEY (hourly_window_id) REFERENCES public.explore_provider_usage_windows(id);

--
-- Name: explore_provider_usage_reservations explore_provider_usage_reservations_monthly_window_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_reservations
    ADD CONSTRAINT explore_provider_usage_reservations_monthly_window_id_fkey FOREIGN KEY (monthly_window_id) REFERENCES public.explore_provider_usage_windows(id);

--
-- Name: explore_provider_usage_reservations explore_provider_usage_reservations_policy_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_reservations
    ADD CONSTRAINT explore_provider_usage_reservations_policy_revision_id_fkey FOREIGN KEY (policy_revision_id) REFERENCES public.explore_provider_usage_policy_revisions(id);

--
-- Name: explore_provider_usage_windows explore_provider_usage_windows_budget_scope_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_windows
    ADD CONSTRAINT explore_provider_usage_windows_budget_scope_fkey FOREIGN KEY (budget_scope) REFERENCES public.explore_provider_usage_policies(budget_scope);

--
-- Name: travel_document_extractions fk15sohwvos7dd6sx7bj45dmk23; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_document_extractions
    ADD CONSTRAINT fk15sohwvos7dd6sx7bj45dmk23 FOREIGN KEY (travel_document_id) REFERENCES public.travel_documents(id);

--
-- Name: itinerary_items fk2j8a7vxner7x6elmnwmtim18h; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_items
    ADD CONSTRAINT fk2j8a7vxner7x6elmnwmtim18h FOREIGN KEY (activity_id) REFERENCES public.activities(id);

--
-- Name: activities fk2oooqkhm3kxy3yijondygnl9x; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activities
    ADD CONSTRAINT fk2oooqkhm3kxy3yijondygnl9x FOREIGN KEY (destination_id) REFERENCES public.destinations(id);

--
-- Name: flight_segments fk3wun4rnenagcl1hbayyo3jk7l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flight_segments
    ADD CONSTRAINT fk3wun4rnenagcl1hbayyo3jk7l FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

--
-- Name: trip_members fk652lcpk0gdigjfm28bhgu24y2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trip_members
    ADD CONSTRAINT fk652lcpk0gdigjfm28bhgu24y2 FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: travel_documents fk_document_uploader; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_documents
    ADD CONSTRAINT fk_document_uploader FOREIGN KEY (uploaded_by_user_id) REFERENCES public.users(id);

--
-- Name: explore_provider_usage_policies fk_explore_usage_active_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_policies
    ADD CONSTRAINT fk_explore_usage_active_revision FOREIGN KEY (budget_scope, active_revision_id) REFERENCES public.explore_provider_usage_policy_revisions(budget_scope, id);

--
-- Name: explore_provider_usage_policy_revisions fk_explore_usage_previous_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_policy_revisions
    ADD CONSTRAINT fk_explore_usage_previous_revision FOREIGN KEY (budget_scope, previous_revision_id) REFERENCES public.explore_provider_usage_policy_revisions(budget_scope, id);

--
-- Name: explore_provider_usage_windows fk_explore_usage_window_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explore_provider_usage_windows
    ADD CONSTRAINT fk_explore_usage_window_revision FOREIGN KEY (budget_scope, applied_policy_revision_id) REFERENCES public.explore_provider_usage_policy_revisions(budget_scope, id);

--
-- Name: reservations fk_reservation_confirmed_extraction; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fk_reservation_confirmed_extraction FOREIGN KEY (confirmed_extraction_id) REFERENCES public.travel_document_extractions(id);

--
-- Name: destinations fkb8tdm3ktpurxolcu3ip0pemuo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.destinations
    ADD CONSTRAINT fkb8tdm3ktpurxolcu3ip0pemuo FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: trip_members fkbcm7ddnlofwaqe6t6hjfm80u7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trip_members
    ADD CONSTRAINT fkbcm7ddnlofwaqe6t6hjfm80u7 FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: itinerary_items fkc60yo48d93wun6tvdserpoqsx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_items
    ADD CONSTRAINT fkc60yo48d93wun6tvdserpoqsx FOREIGN KEY (itinerary_id) REFERENCES public.itineraries(id);

--
-- Name: travel_documents fkfdd4bauntd391btjhm3d1rowf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.travel_documents
    ADD CONSTRAINT fkfdd4bauntd391btjhm3d1rowf FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: itinerary_conflicts fkfwi5s6sdjxo7e0yyg04s5y19d; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_conflicts
    ADD CONSTRAINT fkfwi5s6sdjxo7e0yyg04s5y19d FOREIGN KEY (second_item_id) REFERENCES public.itinerary_items(id);

--
-- Name: itinerary_conflicts fkk847tc67q3ven3t26bnbrfpg4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_conflicts
    ADD CONSTRAINT fkk847tc67q3ven3t26bnbrfpg4 FOREIGN KEY (trip_member_id) REFERENCES public.trip_members(id);

--
-- Name: activities fkll3fqd2jlhvfyqko0w2axgvca; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activities
    ADD CONSTRAINT fkll3fqd2jlhvfyqko0w2axgvca FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: itinerary_item_participants fkoi6fc4mkvh328kf7rr8mfpiiy; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_item_participants
    ADD CONSTRAINT fkoi6fc4mkvh328kf7rr8mfpiiy FOREIGN KEY (itinerary_item_id) REFERENCES public.itinerary_items(id);

--
-- Name: itinerary_conflicts fkpu10ta7u485eh2te3ldj85gi9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_conflicts
    ADD CONSTRAINT fkpu10ta7u485eh2te3ldj85gi9 FOREIGN KEY (first_item_id) REFERENCES public.itinerary_items(id);

--
-- Name: reservations fkqw64rkf94u8wiele34h6a66y1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fkqw64rkf94u8wiele34h6a66y1 FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: itineraries fkqyn9ljarjth89gh4a9bvydepp; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itineraries
    ADD CONSTRAINT fkqyn9ljarjth89gh4a9bvydepp FOREIGN KEY (trip_id) REFERENCES public.trip(id);

--
-- Name: itinerary_item_participants fks51fggmmthkyx68m1ipb46ppl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_item_participants
    ADD CONSTRAINT fks51fggmmthkyx68m1ipb46ppl FOREIGN KEY (trip_member_id) REFERENCES public.trip_members(id);

--
-- Name: itinerary_items fktd1mkgcb7qtxooirq07thau6n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.itinerary_items
    ADD CONSTRAINT fktd1mkgcb7qtxooirq07thau6n FOREIGN KEY (reservation_id) REFERENCES public.reservations(id);

--
-- Name: reservations reservations_correction_review_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_correction_review_id_fkey FOREIGN KEY (correction_review_id) REFERENCES public.tour_correction_reviews(id);

--
-- Name: reservations reservations_endpoint_clarification_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_endpoint_clarification_actor_id_fkey FOREIGN KEY (endpoint_clarification_actor_id) REFERENCES public.users(id);

--
-- Name: reservations reservations_endpoint_update_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_endpoint_update_id_fkey FOREIGN KEY (endpoint_update_id) REFERENCES public.activity_endpoint_history(id);

--
-- Name: tour_correction_reviews tour_correction_reviews_accepted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tour_correction_reviews
    ADD CONSTRAINT tour_correction_reviews_accepted_by_fkey FOREIGN KEY (accepted_by) REFERENCES public.users(id);

--
-- Name: tour_correction_reviews tour_correction_reviews_actor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tour_correction_reviews
    ADD CONSTRAINT tour_correction_reviews_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES public.users(id);

--
-- Name: tour_correction_reviews tour_correction_reviews_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tour_correction_reviews
    ADD CONSTRAINT tour_correction_reviews_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.travel_documents(id);

--
-- Name: tour_correction_reviews tour_correction_reviews_extraction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tour_correction_reviews
    ADD CONSTRAINT tour_correction_reviews_extraction_id_fkey FOREIGN KEY (extraction_id) REFERENCES public.travel_document_extractions(id);

--
-- Name: user_auth_identities user_auth_identities_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_auth_identities
    ADD CONSTRAINT user_auth_identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- PostgreSQL database dump complete
--

