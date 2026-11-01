# Compact V3.4 model response — offline implementation

Prepared 2026-10-28. No provider call, AWS operation, processing job, deployment or persistent data change was made. Defaults remain `AUTO_V3_4`; historical jobs keep their saved decisions. Existing local extraction-timeout handling is preserved.

## Measured benefit and decision

Document 57's saved completed response is already minified. The final compact representation is **24,351 characters/UTF-8 bytes versus 31,317: 22.24% smaller**, with exact JSON/domain round trips. Its 62 evidence occurrences use 44 association records and 33 quotation/page records. The known invalid participation `noticeMinutes` and missing participants remain invalid/missing in that saved result; conversion does not repair them.

A separately labelled compliant mock fixture, including participant counts, reduces **33,436 → 26,056 characters (22.07%)**. Eight existing complex fixtures reduce **16.87–25.57%**, preserving lifecycle/replacements, uncertainty, conditional boundaries, calculations, supplemental details and scopes.

Full strict-schema JSON is **17,053 characters; compact schema 19,352 (+13.48%)**. A fixed tagged-value shape avoids the much larger union schema from the earlier lean prototype. These are exact character/byte counts, not provider-token estimates. Longer instructions and the schema increase input work. No speed improvement or under-50-second result is established. Output reduction is sufficient to justify an opt-in implementation and a separately approved direct model evaluation, not production activation.

## Wire contract and reconstruction

`CompactSemanticTourV3` defines strict wire version 1:

- `sources`: exact quotation and reported-page pairs.
- `evidence`: source index, explicit booking index and booking association. Different pages/associations remain separate, even for identical words.
- Existing ordered `bookings`, `supplementalFacts` and `supplementalCoverageWarning`.
- Each fact has `kind`, `value`, `uncertainty`, `evidence` indices, literal `sourceExpressions`, and nullable `interpretation` containing dependencies/calculation. `kind` explicitly encodes resolution and basis. For unresolved facts, `value` is the alternatives array; no candidate is selected. MISSING explicitly supplies null value/interpretation and empty evidence/expressions.
- Typed supplemental detail has type/value/unit. Inactive full-domain detail slots are constructed as null.

Java constructs only structural defaults, schema version and trusted provenance, then performs indexed lookup. All interpreted values, arithmetic results/operands, dependencies, evidence identity, uncertainty, lifecycle and scopes remain model decisions. Calculations are **not recomputed to replace an incorrect model value**. Strict decoding, existing V3.4 validation, source hash binding and page-aware quotation grounding run before existing transactional finalization.

Invalid tags, duplicate JSON keys, extra fields, bad/dangling indices, unused source/evidence rows, contradictory MISSING fields, oversized expansion and invalid semantic output fail closed. Expanded evidence is bounded before full serialization. There is no repair request, full-response fallback, added retry, timeout/model change or silent downgrade. Missing facts the model never emits cannot be proven absent from the original source by this codec; live completeness comparison remains necessary. Scans/images retain existing unverified-text handling rather than claiming deterministic quotation verification.

## Integration and compatibility

New durable selections are `AUTO_V3_4_COMPACT` and `SEMANTIC_V3_4_COMPACT`. Classification resolves the new automatic identity once, with existing job/attempt fencing. Tours select compact; flights retain `FLIGHT_V1`; unsupported categories remain unsupported. Retries/restarts read the persisted decision, not current producer configuration. The existing automatic identity still selects the original full representation.

Stored JSON remains the full V3.4 shape. Provenance truthfully identifies generation as `tour-semantic-3.4-compact-1`. Backend readers, lifecycle/confirmation gates and two frontend identity allowlists explicitly recognize that identity; authorization, eligibility, receipts and mutation rules are unchanged. Confirmed Booking Information/Ask AI source retrieval retains original facts, applicable supplemental information and privacy checks. Unconfirmed-extraction Ask AI access is not added.

`db/manual/2026-10-28-document-processing-compact-contract.sql` is **prepared, not applied**. It extends only the existing job-contract check and one-time resolution trigger, preserving old mappings, job/category guards, NULL-as-legacy and immutability. No columns/tables, data rewrites or backfills. Native PostgreSQL migration execution remains unverified; H2 worker checks exercise application routing/transactions, not PL/pgSQL.

A future release must review/apply that migration, deploy all compatible API readers/Lambda consumers and matching frontend, and only then deliberately set new producers to `AUTO_V3_4_COMPACT`. Keep the default unchanged pending real-model approval. Whole-working-tree packaging must separately account for the existing unapplied timeout-outcome migration; do not accidentally deploy its model changes without its prerequisite schema.

Rollback stops producing compact jobs but retains compatible readers/consumers for pending compact jobs and stored results. Unchanged older API/frontend artifacts reject the new identity and are not safe general rollback targets. Never rewrite saved decisions, discard evidence or reset old jobs to make rollback possible.

## Offline evidence

Checks and exact task diffs are under `build/v34-compact-contract-20261028/`; no permanent tests were added.

- 10 compact checks: saved-response losslessness/size, compliant participant fixture, eight complex fixture round trips, invalid output/omissions, associations/pages/quotes, overflow/expansion bounds, one mocked adapter call, current-vs-historical identity, and text PDF/scanned PDF/JPEG/PNG/mixed input paths.
- 33 worker checks (28 inherited existing regressions, five compact cases): persisted routing, classification reuse, retries after configuration change, duplicate delivery, stale finalization, historical compatibility and unsupported categories. A temporary mock-restubbing error was corrected and the failing case independently re-run successfully.
- 46 existing/adapted contract, source, lifecycle and authorized Booking Information checks passed. Tests containing inlined old instruction constants were recompiled before their successful recheck.
- One existing in-memory confirmation fixture adapted to compact provenance passed partial save, provisional rejection, duplicate protection and immutable source checks. All database writes were confined to existing disposable H2 test contexts, not development/AWS data.
- Normal offline `compileJava` passed. Frontend production build passed to a separate build directory using Vite's native config loader. The bundled config loader hit the existing plugin/Vite `vite/internal` resolution issue; no dependency/configuration changes were made to address it.
- Existing frontend checks: **30/38 passed; the same eight failures reproduce with the pre-task identity gates**. Compact-identity adaptation: **12/16 passed; the same four partial-review presentation expectations fail**. These pre-existing UI/test discrepancies were not fixed or hidden. Temporary frontend-source test was removed.

The 90 successful backend checks are across focused runs/rechecks, not a broad-suite result. No live browser or provider compatibility/accuracy/latency claim is made. A future one-request direct evaluation must compare all source bookings, participants, instructions/policies, lifecycle, uncertainty and associations, validate quotations/semantics, and record total duration and tokens. One success would still not establish general model reliability.

## 2026-11-01 — ending variants and instruction tightened (prompt version unchanged)

`PROMPT_VERSION` stays `tour-semantic-3.4`. Local reproduction failed validation with
`Endpoint meaning/location mismatch`: the generation schema allowed an ending location with any meaning, but the
validator requires a location exactly for `DIFFERENT_LOCATION`. The V3.4 `ending` definition is now an `anyOf` of
`DIFFERENT_LOCATION` with a required location and `SAME_AS_MEETING_POINT`/`UNKNOWN` with a null location, and the
instructions state that rule. Validation rules are unchanged. If the combination still arrives, freshly generated
output (not stored extractions) is reconciled to an `AMBIGUOUS` ending that lists each consistent reading, so the
existing ending clarification decides instead of failing the whole document. Provenance does not distinguish the
earlier prompt text from this one.
