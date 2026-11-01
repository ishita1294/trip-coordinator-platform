> **AWS-DEV actual state, 2026-10-30:** timeout-recovery release API44 and matched Lambda deployed: extraction<=90s (remaining invocation minus30s), classification20s, Lambda180s, API/Lambda lease240s, SQS visibility1080s, batch1/window0, max deliveries5. Two committed extraction timeouts become terminal EXTRACTION_TIMEOUT; explicit new jobs reset atomically. Earlier figures below include historical proposals. See `build/timeout-recovery-90-release-20261029/RELEASE.md` and `ROLLBACK.md`. This is reliability headroom, not measured latency improvement.

# Document-processing execution budget

> Historical proposal superseded on 2026-10-25: the 240/300/1440 values below describe the preserved earlier offline work. Current source uses 120/180/720, provider caps 20/60 and reserve 30. Use the Phase 2 section in [the current runbook](AWS_DEV_DOCUMENT_PROCESSING_TEST_BUDGET_REVIEW.md) for release decisions; do not deploy the earlier ZIP or saved plan.


Implemented offline on 2026-10-22. Not deployed; no AWS setting, database row or document 52 processing history was changed.

## Why the budget changed

Document 52's five invocations each hit the former 60-second Lambda deadline. Spring startup used about 24 seconds and first classification about 7 seconds, leaving too little time for V3.4 extraction. Platform termination prevented normal failure persistence. The installed OpenAI Java 4.70.0 sources confirm defaults of 60 seconds for connecting and 600 seconds for the complete request (read/write inherit the request limit). The document clients previously set SDK retries to 0 but did not override these deadlines.

## One bounded attempt

| Setting | Value |
|---|---:|
| Lambda deadline |240 seconds|
| Startup/storage allowance |40 seconds (planning allowance, not a new startup watchdog)|
| Classification complete HTTP call |30 seconds maximum|
| Tour/flight extraction complete HTTP call |120 seconds maximum|
| HTTP connect / write |5 / 10 seconds maximum|
| HTTP read |Same as that request's complete-call limit|
| Time retained after provider work |40 seconds minimum, based on Lambda's live remaining time|
| Additional planning margin |10 seconds|
| Processing lease and final-attempt recovery cutoff |300 seconds|
| SQS queue default visibility, batch 1/window 0 |1440 seconds (6 × 240)|
| SQS deliveries / SDK automatic retries |5 / 0 (unchanged)|

The 240-second budget is 40 startup/storage + 30 classification + 120 extraction + 40 persistence + 10 margin. It provides a bounded 120-second extraction opportunity rather than the observed 27–35 seconds. Successful model latency has not been established by the failed acceptance test, and 120 seconds is not a provider guarantee. Slow calls fail safely rather than consuming the SDK's previous ten-minute allowance.

`DocumentProcessingBudget` installs a thread-local remaining-time supplier around the entire Lambda handler, before Spring initialization. The supplier is read when each request starts; startup, previous stages and previous requests therefore reduce the available time. Request options use the smaller of the stage limit and Lambda remaining time minus 40 seconds. No provider call starts if that reserve is already exhausted. The scope is restored/removed when the invocation exits, including exceptions; no executor or asynchronous repair path was added. Non-Lambda calls retain the explicit stage caps.

The SDK's `Timeout` sets connect, read, write and complete-request limits. The installed OkHttp adapter applies the last value as `callTimeout`, covering the complete HTTP exchange. With SDK retries 0, there is no hidden repeated provider-call budget. Timeout causes are classified by exception types, never document words or provider payload matching.

## Persistence, recovery and diagnostics

Provider timeout follows the existing separately committed failure transaction. A retryable attempt becomes QUEUED with `TECHNICAL_FAILURE`, incremented failure counters and cleared attempt/lease fields. On existing attempt/delivery exhaustion it becomes FAILED and the job's failure marker is recorded. No new database error code is introduced: the current PostgreSQL constraint allows only the existing codes. Safe stage logs distinguish `PROVIDER_TIMEOUT` from `INVOCATION_BUDGET_EXHAUSTED` and identify the stage. SDK error messages and response bodies are not logged.

Finalization and failure transactions have 10-second transaction limits. Lambda uses a 5-second pool-acquisition limit; its RDS connections have 5-second connect, 10-second socket, 10-second statement and 3-second lock limits. PostgreSQL-specific driver settings are applied only to RDS URLs. These are connection/session settings, not schema changes. The 40-second reserve accommodates validation and a failed finalization followed by failure persistence. Database unavailability, process death or unbounded non-provider infrastructure startup can still prevent normal persistence; existing fencing and final-attempt recovery remain the fallback.

The default API/worker lease is 300 seconds. Production claim/recovery construction rejects shorter leases, preventing a new 240-second invocation from being reclaimed under the old 120-second lease. Final-attempt recovery remains idempotent and records RETRIES_EXHAUSTED only after the lease expires. It does not enqueue or replay work. The queue default remains 1440 seconds, but the handler now installs per-delivery startup and lease safeguards, then shortens visibility for committed controlled failures. The detailed retry design below supersedes the earlier visibility-expiry-only retry behavior; ordinary controlled failures no longer intentionally wait 24 minutes. Retry counts have not been increased.

Logs record worker-context initialization time/remaining budget, classification and extraction start/completion/failure, finalization start/completion/failure, and sanitized timeout reasons. Existing source hash, contract identity, generation/attempt fencing, transactional insertion and immutable extraction behavior remain unchanged. No prompt/schema/UI changes were made.

## Focused offline evidence

See the task's focused Gradle test reports under `build/test-results/test`. Coverage includes complete-call timeout against a localhost-only HTTP stub with no SDK retry; explicit deadline phases; live remaining-time clipping; scope/thread isolation; insufficient budget; minimum lease and Terraform/application consistency; actual non-web worker bootstrap with H2 validation; RDS deadline property wiring without connecting to RDS; successful V3.4, legacy/V2 SDK paths; persisted timeout/retry/success; five-attempt exhaustion; classification timeout; stale-attempt fencing; atomic rollback; and expired-final-attempt recovery. Only selected tests were run. No OpenAI or AWS test requests were made.

References: [OpenAI Java timeouts](https://developers.openai.com/api/reference/java#timeouts) and [AWS SQS/Lambda configuration](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html). AWS recommends visibility of at least six times Lambda timeout, plus any batching window. This mapping retains a zero batching window and batch size 1.

## Deployment requirements (not executed)

1. Review a normal integrated API/Lambda build and checksums. Previously frozen deployment artifacts contain the old timeout implementation; do not reuse them as if they contain this correction.
2. Under separate deployment approval, pause ingress/consumption and inventory active jobs. Preserve document 52, its failed generation, historical document 7 and all DLQ messages. Do not requeue or replay them.
3. Increase existing source visibility to 1440 seconds before increasing the Lambda deadline. Keep batch 1/window 0 and redrive 5.
4. Deploy the compatible API/recovery configuration with `DOCUMENT_PROCESSING_WORKER_LEASE_SECONDS=300` before running longer Lambda attempts. The default is 300; reject an explicit stale 120-second override. Preserve all other existing runtime settings, authentication and feature flags.
5. Deploy the compatible Lambda code, timeout 240 seconds and lease 300, retaining 1024 MB and all roles, networking and secrets. The handler still disables publishing/local consumption inside Lambda.
6. Verify startup, actual effective settings, readiness and idle-work gates before resuming the existing mapping. A root Terraform apply is not automatically safe: prior release records identify unrelated ECS/image/publisher drift. Review the exact scoped plan rather than applying unrelated infrastructure changes.
7. Obtain separate approval for one controlled live acceptance test. Do not reprocess document 52 or promise success within 120 seconds before that evidence exists. Observe request completion or a persisted timeout outcome; do not perform blind paid retries.

No migration, new table, queue, service or alternate pipeline is required. API and Lambda should be deployed coherently. Never restore a 120-second API recovery lease while 240-second invocations may still be running; stopping new deliveries does not terminate existing invocations.

## Exact file scope

Production and configuration:

- `src/main/java/com/ishita/tripcoordinatorplatform/ai/document/DocumentProcessingBudget.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/ai/document/OpenAiDocumentClassifier.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/ai/document/OpenAiAttractionTourDocumentExtractor.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/ai/document/OpenAiFlightDocumentExtractor.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/lambda/DocumentProcessingLambdaHandler.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingWorkerExecutionService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/TravelDocumentProcessingService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingWorkerClaimService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingExhaustionRecovery.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/LocalDocumentProcessingConsumer.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingFinalizationService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingFailureService.java`
- `src/main/resources/application.properties`
- `infra/terraform/lambda.tf`
- `infra/terraform/main.tf`

Local tests (unstaged):

- `src/test/java/com/ishita/tripcoordinatorplatform/ai/document/DocumentProcessingBudgetTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/ai/document/AttractionTourResponseTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/ai/document/SemanticTourV3ContractTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/ai/document/SemanticTourExtractionTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/lambda/DocumentProcessingDeliveryTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/lambda/DocumentWorkerBootstrapTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/service/DocumentContractWorkerIntegrationTest.java`
- `src/test/java/com/ishita/tripcoordinatorplatform/service/DocumentWorkerPostgresTest.java`

`DocumentWorkerPostgresTest` only updates its lease fixture to 300 seconds/expiry 301 seconds; the PostgreSQL suite was not rerun. This runbook is the only documentation addition for this correction.


## Retry finalization — 2026-10-22 (offline, not deployed)

### Options and selected design

| Approach | Decision |
|---|---|
| Globally reduce queue visibility to a few minutes | Rejected: would discard AWS's six-times recommendation and its protection for throttled/undispatched invocations. |
| Persist receipt handles so the API coordinator can change visibility | Requires new durable delivery metadata, handling stale receipts and sensitive delivery capabilities; unnecessary for ordinary handler-started crashes. Not implemented. |
| Republish stale jobs from the existing outbox | Could reuse generation fencing, but creates duplicate original/replacement deliveries, resets SQS receive counts and needs careful durable retry timing to avoid racing controlled backoff. Not implemented. |
| Protect each current delivery before bootstrap and after its atomic claim | Selected: reuses the original SQS message, existing claim lease and final-attempt coordinator. No new table, queue, coordinator or receipt persistence. |

`DocumentProcessingVisibility` performs only ChangeMessageVisibility. It validates the configured source queue and uses only the current event receipt; neither receipt nor AWS response payload is logged. The SQS management call has a 3-second total/2-second attempt limit and no SDK retry. Production Lambda requires `DOCUMENT_PROCESSING_SQS_QUEUE_URL` and source-queue-only `sqs:ChangeMessageVisibility`; these are prepared in Terraform but not applied.

1. Before Spring/secrets initialization, install visibility for the live remaining invocation time plus 60 seconds: at most 300 seconds for a 240-second invocation. No provider work is authorized if this call fails. This deadline safely exceeds the entire remaining Lambda lifetime, including a killed bootstrap.
2. An atomic claim returns its actual lease expiry. Before provider work, set visibility to that expiry plus 30 seconds: normally about 330 seconds after claim. The original worker must have terminated by 240 seconds from invocation entry, before its 300-second claim lease expires. The queue can therefore redeliver after lease expiry without overlapping a valid worker. A failed lease-protection call starts no provider work.
3. After failure persistence commits, the worker throws a sanitized exception carrying RETRY or FAILED. Only that committed result permits fast redelivery. RETRY uses 15/30/60/120 seconds on receives 1–4; FAILED uses 15 seconds to advance toward the existing DLQ policy. The Lambda handler still throws in every unfinished/failed case, whether visibility adjustment succeeds or fails. There is no acknowledgement, new message or outbox republish.
4. An ALREADY_ACTIVE duplicate receives the authoritative existing lease expiry and defers its own receipt until expiry plus 30 seconds. It never executes providers or clears the owner. This also covers a crash between claim commit and installing the claim-specific visibility safeguard.
5. Duplicate receives count toward SQS redrive even when no new database attempt starts. Record the highest observed receive count monotonically for the matching active generation. Controlled failure then honors that count; if the owner dies, the existing exhausted-lease coordinator can close the job. Claims cannot start new work after the delivery or attempt cap is reached. An existing owner can still commit a valid success. Without this check, duplicates could exhaust SQS while leaving a nonterminal database job stranded.

### Eligibility timelines, not execution SLAs

| Event | Persisted state and next eligibility |
|---|---|
| Controlled provider timeout | Failure transaction commits QUEUED; current receipt becomes eligible 15, 30, 60 or 120 seconds after the successful visibility update. No additional 300-second lease wait: the failed attempt was cleared. |
| Crash after startup safeguard but before claim | Original message becomes eligible by the startup safeguard, at most about 300 seconds from installation. Document remains queued; no provider work preceded the claim. |
| Crash after claim safeguard | Original message becomes eligible at claim lease expiry +30 seconds, normally about 330 seconds after claim. A new atomic claim can reclaim the stale owner. |
| Crash between claim and claim safeguard | Startup safeguard remains. If it exposes the message before lease expiry, the next handler detects ALREADY_ACTIVE and defers to that existing lease +30 seconds. It performs no provider work. |
| Example full 240-second Lambda timeout, claim at 24 seconds | Message eligible around invocation time 354 seconds: roughly 114 seconds after termination, not 20 minutes. |
| Controlled terminal/exhausted failure | FAILED is committed immediately. No more provider work. Receipts remain unacknowledged and use 15-second intervals until normal redrive moves the original message to the DLQ. |
| Killed exhausted attempt | Existing API coordinator marks FAILED/RETRIES_EXHAUSTED after the 300-second lease plus its nominal 0–30-second scheduler interval. No retry is created. |
| Fast retry adjustment unavailable after lease safeguard | Invocation still fails; original message remains protected until the last successful safeguard, normally lease expiry +30 seconds. |

Lambda poller backoff, throttling, scheduler/database outages and bootstrap latency can add to eligibility. These are not guaranteed wall-clock start times. The tests prove handler signaling and timing requests against mocked SQS; they do not claim to have exercised live AWS redelivery.

### Remaining external boundary

A runtime/init failure before the handler can install any safeguard, a missing/invalid queue setting or permission, or failure of the initial ChangeMessageVisibility call retains the queue's 1440-second fallback. No provider work is started in the handler if initial protection fails. Undispatched/throttled messages also retain that AWS-recommended default. This exception is explicit: the implementation is not a guarantee of fast recovery before application code can execute or when SQS management is unavailable. An absolute sub-24-minute guarantee for those cases would need a separately reviewed departure from the queue recommendation or a durable external coordination design. No such infrastructure, persistence or automatic replay was added.

HTTP cancellation also does not prove OpenAI has stopped upstream computation. Database ownership/fencing prevents overlapping authoritative worker commits; it is not a guarantee about cancellation or billing inside the provider.

### Verification and deployment boundary

Focused offline tests cover all backoff intervals, unchanged receipt identity, Lambda throwing after retry/terminal failure, failed visibility adjustments, failed failure-persistence, startup and claimed-attempt crashes, active duplicates, concurrent stale claims, observed delivery exhaustion, owned success, stale/double finalization, terminal recovery and unchanged outbox contract. SQS is mocked and database tests use H2; no real AWS or OpenAI call or document 52 processing was performed. Terraform formatting and source whitespace checks are local only.

Deploy only after separately approving the coordinated execution-budget release. The Lambda must receive its queue URL and scoped ChangeMessageVisibility permission before this code is activated. Preserve queue default 1440, timeout 240, lease 300, batch 1/window 0 and five deliveries. No migration or event-source response-type change is required; the handler remains a whole-batch failure adapter. Preserve document 52 and all existing DLQ messages.

Retry-specific code/configuration scope:

- `src/main/java/com/ishita/tripcoordinatorplatform/lambda/DocumentProcessingVisibility.java` (new)
- `src/main/java/com/ishita/tripcoordinatorplatform/lambda/DocumentProcessingLambdaHandler.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingException.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingWorkerExecutionService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingWorkerClaimResult.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingWorkerClaimService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/service/DocumentProcessingFailureService.java`
- `src/main/java/com/ishita/tripcoordinatorplatform/repository/TravelDocumentRepository.java`
- `infra/terraform/lambda.tf`
- Local tests: `DocumentProcessingRetryTest`, `DocumentProcessingDeliveryTest` (reused unchanged), selected `DocumentContractWorkerIntegrationTest` methods.

References: [per-delivery visibility semantics](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ChangeMessageVisibility.html), [Lambda whole-batch failures and poller backoff](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-errorhandling.html), and [queue visibility guidance](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html).


Final retry verification: **29 distinct focused tests passed across targeted runs** (15 mocked-SQS handler/visibility tests, six existing delivery tests, and eight selected H2 worker/claim/recovery tests). Production and test compilation passed. New concurrency verification has one winning stale claim, no stale/double finalization and one immutable extraction. Local polling is explicitly excluded from SQS duplicate receive-count observation. Terraform formatting and changed-line whitespace checks passed. No PostgreSQL suite, live Lambda/SQS test, deployment or model request was run. These are offline guarantees about the adapter and transactions, not a measured AWS redelivery SLA.

If all deliveries fail before any claim, the database cannot infer SQS exhaustion from a claim it never received; the message can reach the DLQ while the document remains QUEUED. This is another explicit bootstrap/infrastructure boundary, not a successful processing result. A requirement to persist terminal outcomes for failures before the application can reach PostgreSQL needs a separately reviewed durable delivery-observation/coordinator change. That portion is not implemented or described as complete.


## Worker startup exclusions and timing (2026-10-22, offline only)

The Lambda application now adds the `document-worker` profile. Under that profile only, assistant conversation cleanup and six EXPLORE beans are excluded: ExploreService, ExploreScenarios, ExploreRestPlanning, ExploreRequestGuard, FeasibilityEngine and GroundedExploreRanker. Their callers are confined to the EXPLORE web path, which is already absent in the non-web worker. The normal API does not add this profile and retains these beans. Shared providers, all JPA mappings and validate-only database startup remain in place.

Startup logs now distinguish Secrets Manager client creation, each sequential secret load, total Lambda secret loading, Spring environment preparation, context preparation, context refresh and application readiness. Secret logs contain only purpose, status and elapsed milliseconds; no secret values, ARNs or provider error bodies. Existing Hibernate logs can be correlated with the context-refresh interval.

The saved one-shot measurement retained 5,072 aggregate output tokens but did not retain reasoning-token details or the generated JSON. Reasoning versus visible output and response redundancy cannot be established from that record. No prompt, schema, model, deadline or retry settings were changed for this startup task. Offline startup checks demonstrate compatibility, not an AWS cold-start speedup; deployed secret-loading durations, remaining bean initialization costs and end-to-end latency still require observation after an approved release.

Verification: 29 focused offline checks passed (20 document worker contract/integration, two worker bootstrap/property checks, two normal API startup/security checks, three secret timing/redaction checks and two EXPLORE actor-admission checks). The worker bootstrap test enables both assistant and EXPLORE AI flags to verify that the worker profile still excludes the targeted beans. Normal API checks cover bean availability, readiness and authentication requirements; they do not constitute full browser or live-provider acceptance. No external provider or persistent application database was used.
