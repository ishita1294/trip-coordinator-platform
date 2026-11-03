-- PREPARED ONLY. Separate approval required. No tables/policies/domain rows are created or changed.
-- Atomic server-side execution of the existing phase protocol eliminates application/RDS statement round trips.
BEGIN;
CREATE FUNCTION public.explore_google_phase_rpc(s text, command text, payload jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public
SET timezone = 'UTC'
SET lock_timeout = '2s'
AS $$
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
END $$;
REVOKE ALL ON FUNCTION public.explore_google_phase_rpc(text,text,jsonb) FROM PUBLIC;
COMMENT ON FUNCTION public.explore_google_phase_rpc(text,text,jsonb) IS 'Atomic Google phase protocol v1; SECURITY INVOKER; no provider content or policy seeding; requires approved installation.';
COMMIT;
