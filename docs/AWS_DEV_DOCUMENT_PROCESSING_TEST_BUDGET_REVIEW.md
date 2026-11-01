# AWS-DEV document-processing test budget review

2026-10-24. Recommendation only; no code, deployed configuration, database, queue or IAM changes. Documentation is the only repository change made for this review. No processing or model requests were executed.

## Deployed baseline and evidence

Read-only AWS checks confirm account 393450686123, us-east-1:

- Function `trip-coordinator-dev-document-processing`, Active / Successful, java25, 1024 MB, timeout **60 seconds**.
- Code SHA256 (AWS base64): `WFgnP7uKY1yl+Ewzv7AqOiFdrp23kDG/p5rOcs97lKU=`. Revision ID `96826267-fa7e-49e4-bb81-5444f7c4a4bd`.
- This matches `build/startup-only-lambda-release/startup-only-lambda.zip`, SHA256 `5858273fbb8a635ca5f84c33bfb02a3a215dae9db79031bfa79ace72cf7b94a5`. The deployed artifact, not newer working-tree source, was inspected.
- ECS service `trip-coordinator-dev-api` runs task definition **27**, one RUNNING / HEALTHY task, completed rollout, no pending tasks. No new authenticated HTTP/browser regression check was performed.
- Mapping `5ab00c97-51d0-4ec0-af22-6269c2cb2c8c` targets this function and remains **Disabled**, batch 1, batching window 0, whole-batch failure signaling.
- Source queue visibility **180 seconds**, maxReceiveCount **5**, visible/in-flight/delayed **0/0/0**. DLQ **3/0/0**. No messages were received, inspected, replayed or deleted; counts are AWS approximate attributes, not a payload-level inventory.
- Lambda environment retains existing storage, RDS and OpenAI secret references. It has no processing lease override or processing queue URL. No secret value was retrieved.
- Revision 27 uses AWS mode, AUTO_V3_4 and an enabled outbox publisher. No lease override: packaged default is **120 seconds**, as in the deployed worker. An empty source queue alone does not prove there is no pending outbox work; repeat the established read-only VPC database gate immediately before activation.
- IAM simulation permits ReceiveMessage, DeleteMessage and GetQueueAttributes on the source queue, but **implicitly denies ChangeMessageVisibility**. Existing worker code does not call it, so that denial does not break the currently deployed adapter.

The first concurrent Lambda configuration inspection failed with a credential-provider `CreateOAuth2Token` HTTP 429. One sequential read-only retry succeeded. This was not an application failure.

Evidence: deployed ZIP and its release manifests/bytecode checks; `build/startup-only-cold-start-20261024/measurement.json`; actual packaged processing classes and application.properties; installed OpenAI Java **4.70.0** source. The previous offline reliability bundle is not deployed and its 240/300/1440 constants are not adopted here.

## Current budget and retry behavior

The saved fresh-environment invocation measured **26.440 seconds full worker context startup**, including Secrets Manager and Spring. Platform initialization was separately 0.714 seconds. Classification approximately 7.1 seconds and extraction 40.527 seconds came from separate runs, not the same complete AWS invocation.

The combined measured-stage estimate is **74.067 seconds**, before S3 retrieval, claims, validation, finalization and other overhead. Cold processing cannot fit the current 60-second invocation. Warm classification plus extraction is approximately **47.627 seconds**, leaving only 12.373 seconds for everything else; successful warm end-to-end processing has not been measured and cannot be called reliable.

Packaged classifier, tour extractor and flight extractor set SDK retries to zero but no explicit request deadline or Lambda remaining-time guard. SDK defaults are connect **60 seconds**, complete HTTP call **600 seconds**, with read/write inheriting that complete-call limit. The installed OkHttp adapter applies a complete callTimeout. An environment-only Lambda timeout increase cannot correct this mismatch: a provider call could still consume the entire invocation and prevent failure persistence.

The deployed handler atomically claims the persisted generation and attempt, invokes the shared worker and throws on unfinished/failed work. A controlled exception is recorded in a separate failure transaction: retryable work becomes QUEUED / TECHNICAL_FAILURE, its attempt is cleared, and delivery/attempt limits remain enforced. The handler then throws; it does not acknowledge the failed message. Failure cleanup errors also throw. There is no receipt-visibility change or new outbox delivery for that controlled retry.

Consequently, current controlled failure and crash retries depend on the original message becoming visible, normally **180 seconds from receipt**, plus Lambda poller backoff/scheduling/startup. The lease is not an additional delay after a controlled failure because ownership was cleared. A killed attempt remains PROCESSING until reclaimed after lease expiry. The API coordinator closes expired **exhausted** attempts; it does not promptly republish every stale attempt. Its nominal scheduler interval is 30 seconds. Five attempts/deliveries, generation fencing and immutable/source-bound finalization remain essential.

## One recommended approval scope

A configuration-only release is insufficient for predictable failures. Recommend a narrowly scoped budget/receipt-recovery release based on the deployed startup-only baseline, reusing the already implemented safeguards only where listed below. Preserve the bundled reliability implementation and artifacts separately. Do not deploy their ZIP wholesale.

| Item | Proposed value/change | Reason |
| --- | --- | --- |
| Lambda timeout | **120 seconds** | Approximately 74 seconds measured-stage subtotal plus approximately 46 seconds planning headroom. This is a safety ceiling, not a latency target or measured percentile. |
| Worker lease | **180 seconds in both API and Lambda**, `DOCUMENT_PROCESSING_WORKER_LEASE_SECONDS` | Outlasts a full 120-second invocation with 60 seconds margin, including a warm claim near invocation entry. Prevents the coordinator or another delivery reclaiming a still-running owner. Revision-27 constructors accept this value; no new lease schema or API business-code change is required. |
| Classification HTTP call | **20 seconds maximum** | About 2.8 times the measured 7.1 seconds; slow classification must fail predictably. |
| Extraction HTTP call | **60 seconds maximum** | About 1.5 times the measured normal extraction; permits the existing request without treating arbitrary long latency as normal. Same request/schema/model/reasoning, no generation changes. |
| Live provider budget | Clip each stage cap to **Lambda remaining time minus 30 seconds**; refuse a call without reserve | Stage caps are ceilings, not additive entitlements. At measured cold startup/classification, extraction gets about 56 seconds rather than consuming finalization time. Warm calls can receive the full 60 seconds. |
| HTTP phases | Connect **5 seconds**, write **10 seconds**, read no greater than complete-call cap; complete call bounded as above; retries **0** | Bounds the whole exchange, including upload and response, rather than only socket inactivity. Cancellation is not proof the provider stops computation/billing upstream. |
| Persistence protection | Reuse focused failure/finalization transaction limits **10 seconds** and worker-only pool/connection **5 seconds**, socket/statement **10 seconds**, lock **3 seconds** | Makes the planned 30-second reserve meaningful for validation, finalization or failure persistence. Validate combined paths with stalled-database tests; this is not a guarantee of persistence during database outage/process death. Keep normal API connection behavior unchanged. |
| Source queue visibility | **720 seconds**, batch 1/window 0 unchanged | Follows AWS six-times-120 guidance. This is the fallback for throttled/undispatched work, not the desired ordinary retry delay. |
| Per-delivery protection | Reuse current-receipt startup guard = remaining invocation +60 seconds (**at most 180**); claimed/active attempt guard = actual lease expiry +30 seconds (**about 210 after claim**) | A crash after these guards can recover through the same message after the owner is stale, rather than waiting twelve minutes. No republishing or new delivery state. No provider work if initial protection cannot be established. |
| Controlled retry | After committed failure only, visibility **15/30/60/120 seconds** on receives 1–4; terminal failure **15 seconds** toward existing DLQ | Avoids twelve-minute controlled retries when adopting AWS queue guidance. Handler must still throw. If failure persistence did not commit, never shorten to the fast-retry interval or acknowledge work. |
| IAM/environment | Add only source-queue-scoped `sqs:ChangeMessageVisibility`; add existing source URL as Lambda `DOCUMENT_PROCESSING_SQS_QUEUE_URL` | Required by receipt guards and fast retry; currently denied/missing. No new S3, secrets, RDS, queue-creation or DLQ permission is needed for this proposal. Existing source-queue permissions stay intact. |

Keep memory, runtime, VPC, model, prompt, schema, reasoning, extraction-contract resolution/versioning, five-delivery limit, document statuses, source hashes, review/confirmation and reservation behavior unchanged. No database migration is required. Do not change frontend, Cognito, EXPLORE or assistant settings.

Why not only increase timeout: the provider's ten-minute default would still overrun Lambda. Why not retain a 120-second lease: no safety margin for a warm 120-second invocation. Why not only increase queue visibility: controlled failures would wait approximately twelve minutes. Why not adopt the bundled 240/300/1440 values: the observed stages do not justify those ceilings for this controlled test.

This is a coherent recommendation, not an already prepared or tested 120/180 release. Existing bundled code hard-codes larger minimums, so changing AWS values while deploying that ZIP would be incompatible. Prepare and test only this approved scope before deployment; do not merely override its environment.

## Expected failure timelines and boundaries

- Controlled provider timeout: persisted QUEUED/TECHNICAL_FAILURE, then same receipt eligible after 15/30/60/120 seconds. Actual next start also depends on Lambda backoff, scheduling and startup; not a wall-clock SLA.
- Crash after claim guard: message eligible at claim +approximately 210 seconds. For claim at invocation +26 seconds and termination at +120 seconds, eligibility is around +236 seconds, about 116 seconds after termination. Existing atomic stale claim/fencing prevents overlapping valid database owners.
- Crash during startup after guard: eligibility by approximately 180 seconds after guard. A crash between claim and claim guard retains startup protection; an early duplicate must observe ALREADY_ACTIVE and defer to the recorded lease +30 seconds, never execute another provider call.
- Exhausted attempt: no more provider calls; committed terminal outcome remains failed. Killed exhausted work can be closed after lease expiry plus the nominal 0–30-second coordinator interval. Retain the existing observed-delivery exhaustion safeguards when adapting the already implemented retry code, so duplicate receipts cannot strand an active generation after SQS redrive.
- Failure before handler guard, unavailable SQS management, or undispatched/throttled work can retain the **720-second fallback**. This explicit infrastructure boundary is not a guarantee of prompt recovery. Do not introduce another coordinator or persistence mechanism for this test.

## Proposed implementation, validation and deployment order

1. Obtain approval for this exact scope. Prepare one immutable API configuration revision and Lambda artifact retaining startup isolation and the existing rev27 processing/JSON contracts. No extraction optimizations. Reuse the implemented budget/visibility/failure safeguards with the reviewed smaller constants; do not import unrelated changes.
2. Focused offline checks must cover request caps and remaining-time clipping; slow provider and database failure persistence; successful V3.4 source-bound REVIEW_REQUIRED finalization; legacy/flight compatibility; five-attempt exhaustion; whole-batch throw after committed failure; same-receipt backoff; startup/claim crashes; active duplicates, revoked ownership and stale/double finalization. Full worker validate-only bootstrap and normal API wiring must still pass. Old bundled test evidence alone does not verify the newly proposed settings. No live model calls for these checks.
3. With mapping still disabled, check live artifact/config identities, active invocations, source counts and read-only VPC outbox/attempt state. Preserve document 7, failed document 52 and all three DLQ messages. Empty queue does not authorize activation without the database gate. Stop if unexpected work/configuration drift is found.
4. Preserve previous code/config manifests. Apply only source-queue IAM permission and visibility 720; configure API lease 180 first and confirm readiness. This is a configuration replacement of the rev27 API container, not a product-code release. Verify exact scoped change plan, no unrelated drift or resource replacement.
5. Deploy the compatible Lambda artifact with timeout120, lease 180 and source URL. Retain all other settings and startup-only profile. Validate actual packaged limits, permissions, health and queue/mapping states. No migration, SQS test message, empty-event invocation or processing is implied by this review.
6. Enable the existing mapping only after all gates pass and deployment/activation is authorized. Obtain separate paid-test approval. Upload/process one new controlled Museum PDF through the real UI, never reprocess document 52. Observe upload/outbox/publish, invocation/startup, claim, classification, extraction, validation/finalization and REVIEW_REQUIRED with one correlation chain. Review facts/provenance in the UI; do not confirm/create duplicate reservations automatically. Judge successful processing by persisted output, not a changed timeout setting.
7. Stop on provider overrun, failed failure persistence, schema/contract mismatch, duplicate extraction, unexpected job or health regression. Disable new ingress/consumption if authorized; do not replay, delete or manually repair job state. Keep data/evidence for diagnosis.

Rollback: stop new deliveries and wait until invocations and owned leases are drained before reverting code/lease/timeouts. Restore the exact startup-only ZIP only with its matching old configuration after confirming no incompatible in-flight state. Retaining a longer visibility/lease temporarily is safer than shortening below an active owner. Never revert a live worker to an incompatible lease, erase processing history, downgrade persisted extraction contracts or redrive the DLQ. No automatic destructive rollback.

## Readiness decision

**Not ready for a paid end-to-end test under current settings.** The minimum safe release requires bounded provider/failure handling plus compatible configuration and receipt recovery; it is not a timeout-only edit. Approval is needed for the proposal, followed by focused preparation/deployment verification. No current code/AWS change or performance improvement is claimed.

References: [AWS SQS/Lambda queue guidance](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html) (function timeout must not exceed visibility; recommended visibility six times timeout plus batch window), [Lambda failure/backoff behavior](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-errorhandling.html). SDK defaults verified from the installed 4.70.0 `Timeout.kt` and OkHttp adapter, not inferred from the undeployed budget implementation.


## Phase 1 implemented offline — 2026-10-24

Only bounded provider execution and persistence admission were changed in this phase. No AWS, database, queue, IAM, deployment, receipt policy, lease or Terraform changes were made. Existing bundled reliability code and immutable release artifacts were preserved.

### Actual changes and reused safeguards

- `ai/document/DocumentProcessingBudget.java`: classification cap **20 seconds**, extraction cap **60 seconds**, persistence reserve **30 seconds**. Complete-call deadlines remain `min(stage cap, live Lambda remaining time - reserve)`; connect is at most 5 seconds and write at most 10 seconds, both clipped to that complete-call budget. Read and complete-call limits match. No positive provider budget means no provider request. The existing classifier, flight and all tour extraction client paths already use these options and zero SDK retries; their prompts, models, schemas and decoding were not changed.
- The same budget class now checks that **30 seconds remain before success finalization** and **15 seconds remain before failure persistence** (5-second pool acquisition plus 10-second transaction). These are admission checks and planning allowances, not an operating-system kill switch or a guarantee during a database outage. They use the invocation clock and do nothing outside an invocation scope.
- `service/TravelDocumentProcessingService.java`: checks finalization time after source closure, decoding and validation, before calling the finalization transaction. If validation has consumed the reserve, no success transaction starts; the existing worker attempts a separately committed technical failure instead.
- `service/DocumentProcessingWorkerExecutionService.java`: checks failure-persistence time before entering the independent transaction. Insufficient time throws without a committed-failure marker; it cannot signal success or authorize fast retry based on a write that never committed. Ownership remains for existing recovery. Sanitized logging distinguishes persistence budget exhaustion from a provider timeout. A valid success already committed is not retrospectively changed to failure merely because little time remains afterward.
- Reused unchanged: finalization transactions with 10-second limits; failure persistence with REQUIRES_NEW and 10-second limit; Lambda-only 5-second pool/connection bounds and RDS 10-second socket/statement, 3-second lock limits. PostgreSQL settings were checked as properties only, not against a persistent database. Existing attempt fencing, source-hash validation, immutable inserts, rollback, five-attempt limit and whole-batch failure signaling remain in place.

Test files changed locally, not staged: `ai/document/DocumentProcessingBudgetTest.java`, `service/DocumentContractWorkerIntegrationTest.java`, `lambda/DocumentProcessingDeliveryTest.java`. The delivery assertion was updated for the new provider cap/reserve; no receipt logic changed. The integration fixture uses spies around the real transaction services/repository to test timeout rollback after the fenced status update.

### 120-second budget evaluation

| Path | Startup/storage allowance | Classification | Extraction allowance at request start | Reserved persistence |
| --- | --- | --- | --- | --- |
| Measured cold-stage arithmetic | 26.440s worker startup; storage/claims additionally consume live time | 7.100s measured separately | At most **56.460s**, less any other elapsed work | 30s |
| Warm arithmetic | No repeated Spring startup; other overhead still applies | 7.100s | Up to **60s** | 30s |
| Configured worst-case planning example | **35s** combined allowance, not a measured upper bound | Full **20s** | **35s**, not 60s | 30s |

Using the separately measured 40.527-second extraction, cold arithmetic leaves 45.933 seconds after extraction and warm arithmetic leaves 72.373 seconds, excluding other unmeasured work. These are calculations, not new latency measurements or an end-to-end guarantee.

The independently configured stage caps are not additive entitlements. **35 + 20 + 60 + 30 = 145 seconds**, which cannot fit a 120-second invocation. Dynamic clipping reduces extraction to 35 seconds on that worst-case planning path. The request then either completes within the smaller deadline or fails technically and attempts failure persistence. If decoding/validation/source closure eats the finalization reserve, success finalization is refused. No partially decoded output is saved as a successful extraction. A 120-second ceiling is therefore internally consistent for bounded work, but cannot guarantee successful extraction under all configured phase maxima.

### Focused verification

Successful command (offline dependencies, localhost HTTP stub and in-memory H2 only):

```sh
./gradlew test --offline \
  --tests '*DocumentProcessingBudgetTest' \
  --tests '*DocumentContractWorkerIntegrationTest' \
  --tests '*DocumentProcessingDeliveryTest' \
  --tests '*DocumentWorkerBootstrapTest.rdsDeadlinePropertiesAreAppliedWithoutStartingOrConnectingToADatabase' \
  --tests '*AttractionTourResponseTest' \
  --tests '*SemanticTourV3ContractTest' --console=plain
```

**65 tests passed, zero failures/errors/skips**: budget 11, transactional worker integration 25, delivery 6, RDS property wiring 1, existing response/schema regressions 22. Production and test compilation passed. Nine new tests cover cold/warm/worst-case calculations, persistence boundaries, transaction annotations, no API request at exhausted provider budget, late finalization refusal, actual transaction rollback on a mocked repository timeout, insufficient failure-persistence time and committed success near deadline. Existing tests also cover real SDK complete-call timeout with exactly one localhost request, context cleanup, provider failure/retry/exhaustion, legacy/V2/V3.4 contracts, stale attempts, concurrent claims and duplicate finalization.

The first test run found one old delivery expectation still assuming the 40-second reserve; updating that expectation to the approved 30-second reserve resolved it. No production behavior was changed to satisfy that obsolete assertion. Test results are under `build/test-results/test/`.

### Deferred integration and limitations

- No Lambda/lease/queue/receipt configuration was changed. Existing **undeployed** coordination constants remain 240-second maximum invocation / 300-second lease, as do the previous Terraform/default settings. They are not the provider-call deadline source: tests supply a live 120-second Lambda clock. The next coordination phase must explicitly reconcile these constants, API/worker leases, visibility and required IAM/environment before producing a deployable 120/180 release. Do not deploy the current working tree as though that phase were complete.
- The deployed startup-only ZIP is unchanged and still has the old provider behavior. No AWS improvement is claimed.
- This phase does not bound all initialization, Secrets Manager/S3 operations, CPU decoding or driver/native behavior. Time spent there reduces the live budget; when too little remains, retaining the failed receipt/attempt for recovery is safer than acknowledging or inventing a persisted result. Process termination/database unavailability can still prevent recording failure.
- Thirty seconds is a planned persistence reserve, not a hard end-to-end database watchdog. Actual PostgreSQL lock/network/commit behavior needs deployment verification; H2 rollback and property checks do not prove an AWS timing SLA. Cancellation of HTTP does not establish cessation of upstream provider work or billing.
- No paid model request, document processing, persistent-data change, Git staging/commit or deployment was performed. Lease/SQS/retry coordination and live acceptance remain pending review.


## Phase 2 completed offline — 2026-10-25

This section supersedes the deferred coordination settings in Phase 1 and the older offline 240/300/1440 proposal. Current source configuration: **Lambda 120 / lease 180 / queue720 seconds**, with **classification 20 / extraction 60 / persistence reserve 30 seconds**. No AWS operations, deployments, model requests, processing, persistent database changes or Git index operations occurred.

### Exact changes and reuse

- ai/document/DocumentProcessingBudget.java: maximum supported invocation **120**, coordinated minimum lease **180**. Actual provider deadlines still use Lambda's live clock.
- service/DocumentProcessingWorkerClaimService.java, DocumentProcessingExhaustionRecovery.java and LocalDocumentProcessingConsumer.java: injected lease defaults **180**, matching application.properties. Only the local consumer's shared default changed; no transport redesign.
- infra/terraform/lambda.tf: timeout120, lease 180, explicit batch window 0, unchanged batch 1. Mapping activation uses document_processing_consumer_enabled.
- infra/terraform/variables.tf: consumer activation defaults **false**, requiring separate deliberate activation.
- infra/terraform/ecs-api/main.tf: emits exactly one DOCUMENT_PROCESSING_WORKER_LEASE_SECONDS=180; rejects an explicit conflicting value instead of carrying a legacy120 or300 override.
- Source visibility already equals six times the Lambda timeout in infra/terraform/main.tf: now **720**, with window 0. That file required no edit.
- Tests changed locally: DocumentProcessingBudgetTest, DocumentProcessingRetryTest, DocumentContractWorkerIntegrationTest.
- Documentation: this runbook, the supersession notice in DOCUMENT_PROCESSING_EXECUTION_BUDGET.md and the worker README.

Reused unchanged: DocumentProcessingVisibility, Lambda handler, claim result, atomic claims/observed receive counts, transactional worker/failure/finalization and exhaustion coordinator. No duplicate retry mechanism, new table, delivery persistence or scheduler was introduced. Existing receipt protection code already calls ChangeMessageVisibility; merely configuring backoff constants would not have been sufficient.

### Execution/retry lifecycle

1. Before Spring/secrets initialization, validate the source/current receipt and actually change visibility to remaining invocation seconds rounded up +60: at most **180 seconds**. A failed startup guard throws before any claim/provider work.
2. Claim the persisted job generation atomically. Before provider work, protect the current receipt through actual lease expiry +30: normally **210 seconds after claim**. Failed protection throws without provider work. Active duplicates perform no work and defer to the existing lease +30.
3. Run unchanged extraction contracts under the live 20/60-second provider caps and30-second reserve. Only owned transactional finalization reaching REVIEW_REQUIRED permits success acknowledgement.
4. Commit controlled failure independently. Retryable work becomes QUEUED/TECHNICAL_FAILURE; permanent/exhausted/invalid-output-limit outcomes become FAILED. Only a confirmed committed result permits shortening the same receipt to **15/30/60/120 seconds** on receives1–4. Terminal failures use15 seconds toward existing DLQ redrive. The handler still throws: visibility changes are not acknowledgement.
5. Persistence failure or insufficient persistence budget has no committed-failure marker: throw, do not shorten to fast retry, and retain ownership/protection for recovery. Stale completion/failure cannot affect a newer owner.
6. A failed fast-retry or terminal visibility update is logged with reason/cause class only; the invocation still fails. No acknowledgement or provider restart is implied. Last applied protection is the fallback. A transport error may leave the SQS operation's result uncertain, but both prior and requested visibility are safe because fast shortening follows committed failure.
7. A crash after claim protection makes the same message eligible at claim+approximately210 seconds. With a cold claim at invocation+26 and termination at+120, eligibility is approximately+236: **116 seconds after termination**, rather than waiting720 seconds. No outbox republish occurs.
8. API exhausted-attempt recovery waits for the **180-second lease** plus its nominal 0–30-second scheduler interval. New stale claims get a new token. Observed duplicate receive counts remain monotonic, preserving the five-attempt/delivery boundary.
9. Failed terminal deliveries perform no more provider work and remain unacknowledged for existing maxReceiveCount5 redrive. Fifteen seconds is eligibility, not guaranteed DLQ arrival. Poller backoff, throttling and startup add time to eligibility.

ChangeMessageVisibility sets a per-message timeout relative to that API call; it does not alter the queue default or carry its override to the next receipt. Guards are reapplied per delivery. A deterministic test advances failure to receipt+76 and verifies eligibility at+91 (76+15), not+15 or+720.

### Exact IAM/environment requirements

Existing prepared Terraform already scopes the additional action **sqs:ChangeMessageVisibility** to:
arn:aws:sqs:us-east-1:393450686123:trip-coordinator-dev-document-processing

Keep ReceiveMessage/DeleteMessage/GetQueueAttributes on that same source queue. No DLQ access, SendMessage, wildcard SQS action, new secret or new database permission is required. No additional IAM code was needed in this phase.

Lambda environment must retain existing secret/storage/database references and include:
- DOCUMENT_PROCESSING_WORKER_LEASE_SECONDS=180
- DOCUMENT_PROCESSING_SQS_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/393450686123/trip-coordinator-dev-document-processing

API lease must also be 180. AWS SDK uses Lambda's normal region/role credentials. Worker bootstrap still disables publishers/coordinators internally; API retains its existing responsibilities.

### Verification evidence

**125 tests passed, zero failures/errors/skips**:
- DocumentProcessingBudgetTest12
- DocumentProcessingRetryTest19
- DocumentProcessingDeliveryTest6
- DocumentContractWorkerIntegrationTest27
- DocumentWorkerBootstrapTest2 selected checks
- AttractionTourResponseTest7 and SemanticTourV3ContractTest15
- LocalDocumentProcessingTest36 and LocalDocumentLeaseTest1

Production/test compilation passed. Tests use localhost HTTP and in-memory H2 only. Full component-scan worker startup uses an H2 create-then-validate fixture with injected local secrets and no live provider calls. The persistent PostgreSQL bootstrap/schema test was not run.

Coverage includes exact179.999s/180s lease boundary, concurrent claims, stale completion and failure after a new claim, immutable success, provider timeout and failure-persistence errors, actual visibility request values/current receipt identity, startup/claim/terminal adjustment failures, abrupt termination protection, exhaustion and configuration/IAM scope. Tests prove application calls/signaling, not a live AWS redrive SLA.

Final Gradle selectors: DocumentProcessingBudgetTest, DocumentProcessingRetryTest, DocumentProcessingDeliveryTest, DocumentContractWorkerIntegrationTest, DocumentWorkerBootstrapTest.actualNonWebWorkerContextStartsWithoutServletDependencies, DocumentWorkerBootstrapTest.rdsDeadlinePropertiesAreAppliedWithoutStartingOrConnectingToADatabase, AttractionTourResponseTest, SemanticTourV3ContractTest, LocalDocumentProcessingTest, LocalDocumentLeaseTest. Run with test --offline --tests for each selector and --console=plain.

Terraform formatting and **offline validate passed for root and ecs-api**. Initial sandbox validation could not start installed provider plugins; the same validate-only commands succeeded with local execution permission. No init, plan, refresh or apply ran. Configuration tests require matching lease values, queue URL/source-only permission, batch 1/window 0, six-times visibility and disabled-by-default consumption.

### Controlled-release compatibility checklist

- [ ] Recheck live AWS state and idle-work gates under release approval; this phase made no AWS queries. Last evidence: ECS 27 healthy, startup-only Lambda 60/lease 120, source visibility 180, mapping Disabled, source empty, DLQ 3. Preserve document 7, failed document 52 and all existing records/messages.
- [ ] Build fresh immutable compatible API/Lambda artifacts and record checksums. Neither the preserved startup-only ZIP nor old bundled reliability ZIP is this release. No deployment artifact was overwritten here.
- [ ] Keep mapping disabled and prevent new submissions. **The legacy publisher boolean alone does not pause publishing when explicit mode is AWS.** Use the established deliberate ingress/producer pause and read-only outbox gate.
- [ ] Review only intended infrastructure changes. Existing ECS module retains its pre-existing publisher-false validation prerequisite; prepare/review release inputs explicitly rather than assuming this flag stops AWS mode. This phase did not change producer policy or runtime tfvars.
- [ ] Establish source-queue IAM and visibility 720 if the actual batch window remains0. Revision27 processing contracts accept lease 180, but its effective lease 120 must become180 before longer workers are activated.
- [ ] Deploy the compatible new worker with timeout120, lease 180 and queue URL after API recovery configuration is safe. Keep all other security, storage, model, prompt, schema and extraction settings. A stale 240-second invocation now fails before claims rather than quietly outliving the reviewed lease.
- [ ] Verify health, effective API/Lambda settings, IAM, batch/window/redrive and idle work. Consumer activation remains separately approved; default false prevents implicit enablement through the new source configuration.
- [ ] Separately authorize one controlled new PDF test. No reprocessing document 52 or redriving old DLQ messages. Measure the complete real workflow; offline checks do not establish faster processing.
- [ ] Roll back only after stopping new deliveries/submissions and draining invocations/leases. Never reduce lease below a live owner's lifetime, erase processing history or downgrade persisted contracts.

### Remaining limits

Before-handler failure, missing/invalid configuration, unavailable SQS management and throttled/undispatched work can retain the **720-second fallback**. If no claim reaches PostgreSQL, a message may reach the DLQ while its document remains QUEUED; the existing coordinator cannot infer delivery exhaustion it never observed. No new durable observation mechanism or automatic replay was introduced.

The30-second reserve is a planning allowance, not a guarantee through database/network/process failure. HTTP cancellation does not guarantee cessation of upstream provider computation/billing. Dynamic clipping makes 120 seconds internally consistent, but cannot guarantee success at all phase maxima. Actual AWS redelivery and PostgreSQL timing remain deployment-verification requirements.

**Phase 2 offline implementation and focused verification complete. Ready for controlled-release review; not deployed or authorized to consume/process.**


## Controlled AWS-DEV deployment completed — 2026-10-25

The separately approved coordinated release is deployed and verified. **SQS consumption remains Disabled. No document, Lambda test invocation, paid model request or database mutation was submitted.** No migrations, frontend deployment, Git staging/commits or unrelated infrastructure changes were performed.

- ECS API: task definition trip-coordinator-dev-api:**28**, one healthy running task, no pending tasks, completed steady rollout. Only image and DOCUMENT_PROCESSING_WORKER_LEASE_SECONDS=180 changed; previous roles, authentication, feature flags, EXPLORE settings, health probe and rollout policy were preserved.
- Image registry digest: sha256:a4d2b794cc16b1f1277ef70006b89a14650eed8213f9bc15c992d36bb0d20b1e.
- API executable JAR SHA256: 04e9651fd927dd109167b4f92e48fe2a707e9b4128832829d2b83a829c58c96f. The Docker-packaged JAR has the same hash.
- Lambda: trip-coordinator-dev-document-processing, $LATEST, Active/Successful, revision **e048963e-f641-471e-9cfb-8012c6488fe4**.
- Fresh Lambda ZIP SHA256: 3c7bf3a391d5515d68305d07786c13803b8f7dbd3f622d61059de37ab490e933. AWS CodeSha256: PHvzo5HVUV1oMF0HeGwTgDuPfb0/Yi1hBZ3jerSQ6TM=.
- Verified runtime: timeout 120, API/Lambda lease 180, source visibility 720, batch 1/window 0, five-delivery redrive, provider caps 20/60 with live remaining-time clipping and reserve 30. Memory 1024, Java 25/x86_64 Lambda, VPC/role/secret references and all other checked Lambda configuration fields remain unchanged.
- Added only source-queue-scoped ChangeMessageVisibility to the existing worker inline policy. IAM simulation confirms allowed on the intended source ARN. Lambda received only its lease and existing source queue URL additions.
- Existing mapping remains Disabled, source visible/in-flight/delayed 0/0/0; DLQ 3/0/0 unchanged. No message was received, replayed, redriven or deleted.
- Existing ECS/SSM read-only PostgreSQL checks found no pending/active/recoverable jobs. **All 27 public-table counts and fingerprints match before/after exactly**, including documents 7/52, extractions, correction reviews, confirmation receipts, reservations, identities and assistant tables.
- The established application SSM tunnel now targets revision 28 on localhost:8080. Direct readiness and localhost:5173 frontend-proxied readiness both return 200/UP; unauthenticated /trips returns 401. No new browser sign-in or model-backed acceptance is claimed.

Both artifacts were built normally from the current coordinated source; no overlays, old ZIP reuse or source exclusions were used. Fresh Gradle outputs were directed to the release directory, preserving old bundles. All 511 application entries agree between API/Lambda; shared dependencies match; Lambda uncompressed package 156167874 bytes fits its limit. Frozen 285 source/build inputs remained unchanged. Prior 125 focused tests were reused; no broad suite or live provider test was rerun.

A strict registration check initially compared environment lists in order. AWS reordered the same 48 names/values; a read-only comparison proved every value and all other input fields unchanged. The guard was corrected to compare unique environment keys, and the existing registered revision 28 was deployed without another registration. This was a deployment-check normalization issue, not an application incompatibility.

Frozen manifest and execution evidence:
build/worker-reliability-release-20261025/release-manifest.json

No rollback was needed. Revision 27, its immutable image reference, previous startup-only Lambda ZIP/configuration, IAM policy and queue settings are preserved for coordinated rollback after stopping new submissions/deliveries and draining invocations/leases. Do not use the old bundled reliability ZIP or stale Terraform plan.

**Ready for separately authorized SQS activation and one controlled PDF test. Processing cannot run through the disabled mapping until that authorization/action. No processing-speed improvement has been measured by this deployment.**


## Single-attempt acceptance gate — blocked before activation

2026-10-25. The user authorized at most one initial processing attempt, including its required classification/extraction, and explicitly required stopping if the existing retry configuration cannot enforce that bound.

Read-only AWS verification confirms the deployed worker still matches the release checksum and revision e048963e-f641-471e-9cfb-8012c6488fe4. DOCUMENT_PROCESSING_MAX_DELIVERIES is absent, so packaged default5 applies. Source queue redrive is5. Mapping remains **Disabled**, batch 1/window 0; source visible/in-flight/delayed **0/0/0**, visibility 720 seconds; DLQ **3/0/0**, untouched.

The bound is not enforceable by the current configuration. DocumentProcessingWorkerExecutionService and DocumentProcessingFailureService commit a retryable first failure as QUEUED/TECHNICAL_FAILURE when the existing five-attempt cap is not exhausted. DocumentProcessingLambdaHandler then actually changes current-receipt visibility to15 seconds and throws. A later delivery can claim the same job and repeat paid provider work. Claim fencing prevents overlapping owners and duplicate authoritative commits; it does not cap paid work to one attempt. SDK retries0 bound each individual HTTP operation, not the SQS/job lifecycle. Queue visibility 720 is not an escape hatch because per-receipt visibility is explicitly shortened. Watching logs and disabling the mapping after failure cannot guarantee the cap because observation and disable propagation race redelivery.

There is no per-job max-attempt override in DocumentProcessingQueueService.queueDocument or its normal processing request. No job, document, upload or test fixture was created. Fixture selection/isolation, database idle checks and field-level acceptance were not completed after this earlier mandatory gate failed. No database read/change, Lambda invocation, OpenAI request, AWS configuration change, SQS activation, message receive/delete/redrive or Git staging/commit was performed by this attempted test. Only read-only AWS configuration checks and this local record were made.

**Workflow result: NOT RUN / BLOCKED, not an extraction failure.** Stage timings and tokens are unavailable; paid request count is0; no correctness or latency conclusion can be drawn.

Smallest next action for separate approval: temporarily set the existing DOCUMENT_PROCESSING_MAX_DELIVERIES=1 in both Lambda and API, with idle work verified and the test isolated. The handler/claim/failure services then use one attempt; the API exhaustion coordinator also needs1 so an interrupted first claim can become terminal after its lease. Keep SQS redrive5 and historical DLQ messages unchanged. Further failed-message deliveries may still occur, but the handler rejects receiveCount>1 before authorizing provider work. Restore normal5 consistently only after the test is stopped and any retained failed delivery is understood; do not automatically replay that delivery. This uses existing configuration, not a new implementation. No such setting change or deployment was authorized/performed in this task.

Evidence: build/worker-reliability-release-20261025/single-attempt-test-gate.json. SQS was never enabled, so no deactivation was needed.


## Temporary one-attempt test configuration — 2026-10-25

Separately approved configuration-only change: DOCUMENT_PROCESSING_MAX_DELIVERIES=1 in both API and Lambda. Original representation was absent in each environment, with packaged effective default 5. No application/test source, image, ZIP, IAM, queue policy, migration or document-processing behavior was changed.

Verification uses the exact frozen deployed code and **30 unchanged focused offline tests**, covering handler failure signaling, actual receipt visibility requests, exhausted failures, persistence failure, concurrent claims and stale ownership. The one-attempt conclusion follows the deployed comparisons: receiveCount>maxDeliveries rejects further deliveries before claims/provider work; atomic initial/reclaim updates require processingAttemptCount<maxAttempts; at 1 the first successful claim sets count 1 and no second claim can succeed, even for another message with receiveCount1. Normal worker failure handling sees1>=1 and commits terminal failure. API exhaustion recovery also uses 1 for an interrupted first claim. SDK retries remain 0. This proves the per-job cap; it is not a claim that a dedicated live one-attempt extraction was tested.

The temporary API configuration is task definition **29**, same image and task settings as 28 except the added environment flag. Lambda code is unchanged; only its environment gains the same flag. API and Lambda must both be healthy/successful before separately approved test activation. Final live verification evidence is in:
build/single-attempt-configuration-20261025/configuration-manifest.json

The original settings, exact before/after task and Lambda configuration, queue state, read-only database safety fingerprints and readiness evidence are preserved in that directory. The authoritative restoration hold/procedure is:
build/single-attempt-configuration-20261025/RESTORATION_HOLD.md

**Do not restore five while a test message could retry.** Keep consumption disabled, account for every delivery/invocation/job, require no pending/in-flight/delayed source work or pending outbox row, and verify an understood terminal outcome with no active attempt. Source count 0 alone is insufficient. If a retained source/DLQ message, unclaimed QUEUED job or uncertain invocation remains, keep 1 and obtain separate handling approval; do not redrive/delete/reset anything. Only after complete accounting and separate approval, clone the current API task and remove only this override, roll to healthy, then remove only the Lambda override with revision fencing. This restores the original absent/effective 5 setting without reverting unrelated runtime changes. Verify 5 in both while leaving SQS disabled.

Manual reprocessing creates a new generation and is outside the one-test authorization. Failure before claim can yield zero paid calls and a QUEUED job requiring review. No upload, processing, OpenAI request, Lambda test invocation, SQS activation or restoration was performed.

Final verification completed: **API revision 29 healthy**, Lambda revision **590adf88-6e98-4ccf-9d6b-db0554586dcc Active/Successful**, both effective limits 1. Code/image unchanged; Lambda 120 seconds, lease 180 seconds, source visibility 720 seconds, batch 1/window 0 preserved. Mapping Disabled, source 0/0/0, DLQ 3/0/0; read-only processing-data fingerprints and protected documents match before/after, with no pending/active/recoverable work. Direct and frontend-proxied readiness are 200/UP. The established SSM tunnel was reconnected to the new API task without stopping any unrelated process. No rollback or restoration occurred. Ready for **separately approved** controlled test; not begun.


## Controlled one-attempt AWS-DEV acceptance — 2026-10-25

**PASS for Upload → S3/document persistence → outbox/SQS → Lambda → classification → V3.4 extraction/validation → transactional persistence → application review. Evidence-quotation caveat below prevents claiming perfect verbatim citation accuracy.** One new document, one processing generation, one Lambda invocation, one classification request and one extraction request. No retries, benchmark requests, correction reinspection, confirmation, reservation creation or Ask AI call.

Preflight: API revision29 healthy; Lambda revision590adf88-6e98-4ccf-9d6b-db0554586dcc and code SHA2563c7bf3a391d5515d68305d07786c13803b8f7dbd3f622d61059de37ab490e933 match the coordinated release. Both runtimes MAX_DELIVERIES=1, lease180; Lambda120; source visibility720; batch1/window0. Existing ECS/SSM VPC database path confirmed no pending outbox, active attempt or recoverable work. Source0/0/0, DLQ3/0/0. The old localhost8080 tunnel had ended; reconnecting the established tunnel restored frontend readiness200/UP and authenticated /me access. No AWS application configuration changed for that connectivity restoration.

### Fixture and expected result (recorded before activation)

Existing Downloads/Tour_Confirmation_AWS_DEV.pdf, one page, unchanged SHA256 b2619f31b30a0b62ef68efa7c8c6ec9f4a2b7748da8ce0b5050a9bb3196215a7. It describes exactly one confirmed synthetic booking, without contradictory cancellation claims: Paris Historic Quarter Guided Walk; Northstar City Walks (fictional); E2E-PAR-20261022-01; November17,2026 10:00–12:00 Europe/Paris; meet Place Saint-Michel near fountain; end Place des Vosges; two adults; arrive10minutes early; no transport/hotel pickup. Synthetic/non-travel-valid disclaimers are document metadata, not a cancellation. No price, cancellation policy or coordinates stated. Passenger names remain outside the current V3.4 contract.

Normal authenticated frontend localhost5173, Paris E2E Test/trip7: file selected through the file picker; mapping enabled only after safety checks; Upload clicked once; Process clicked once for **new document53**, never historical document52. Job55cddf19-4e7a-4c29-9923-10d04184a1ee / outbox35; extraction66; Lambda request d757ecdb-ceb5-5f6d-b247-8f8c6fe68ef8. AUTO_V3_4 resolved durably to SEMANTIC_V3_4.

### Measured timeline (UTC)

| Event | Timestamp / measured duration |
| --- | --- |
| Browser Upload click | 06:17:38.869 |
| Document upload persisted | 06:17:41.844169; 2.975s from click (combined API/S3 workflow, not separately instrumented) |
| Browser Process click | 06:17:58.868; intentional17.024s observation gap after upload persistence |
| Outbox created | 06:17:59.333506 |
| Outbox published | 06:18:00.457257; 1.124s after creation |
| Platform INIT_START | 06:18:02.427; fresh execution environment |
| Lambda START | 06:18:03.221; 2.764s after publication, including platform init |
| Platform Init Duration | 0.79037s (separate from Spring loaded inside handler) |
| Worker context starting / ready | 06:18:05.558 → 06:18:26.312; 20.753s, includes0.640s secrets stage |
| Storage read | 0.198s |
| Classification | 06:18:26.977 → 06:18:32.650; 5.672s wrapper; provider5.525s |
| Extraction + decoding/validation | 06:18:32.758 → 06:19:06.357; 33.599s |
| Extraction provider request | 33.002s |
| Decoding/validation and other wrapper overhead | Approximately0.597s by subtraction; validation alone is not separately timed |
| Database finalization | 06:19:06.357 → 06:19:06.599; 0.241s measured |
| Lambda END | 06:19:06.602; REPORT duration63.37991s; max memory525MB/1024MB |
| Terminal state observed by safety monitor | 06:19:10.744; consumption disabled and Disabled verified immediately thereafter |
| Application review-required observed | 06:19:27.220; later manual observation, not backend completion time |

**Upload-click → finalization87.730s**, including the deliberate17.024s pause before Process; **Process-click → finalization67.731s**; persisted upload → finalization84.755s. Queue publication was prompt; UI QUEUED also includes worker cold startup before claim. This cold execution exceeded the previous60s ceiling and completed safely under120s. These are single-run observations, not percentile guarantees or proof of a general optimization improvement.

Provider accounting: exactly one classification completion and one extraction completion in the sole invocation; SDK/application retries0 and attempt/delivery counts1. Extraction gpt-6-sol: input5742, output5738, reasoning851, non-reasoning4887. Classification token usage is not logged/persisted by the deployed classifier; full two-request token total cannot be established from available evidence. No additional provider request was made to obtain it.

### Correctness and application verification

Persisted source hash matches the original fixture; prompt tour-semantic-3.4, schemaVersion3, source-bound provenance and provider response retained. One booking/index0; correct name, provider, reference, dates/times/timezone, distinct meeting/ending points, ACTIVE lifecycle and synthetic qualification. Java-checked arithmetic supports09:50 required arrival and120 wall-clock minutes. Two-adult and no-transport/pickup facts retained with booking0 applicability; issue date/synthetic disclaimers remain document-scoped. Coordinates remain null; no price/cancellation policy fabricated; no unresolved warning established for this clear document. Existing production decoder/validator/fenced finalizer accepted the output, producing REVIEW_REQUIRED with zero failure/invalid counts and cleared attempt ownership.

Evidence:26 entries, reported page1 and appropriate booking0/document association.24 match contiguous source text after whitespace normalization. **Two entries (end and END temporal constraint) join the source date and end-time rows while omitting the intervening start-time row. Their values are source-supported, but the quoted text is not one contiguous source span.** Do not describe all quotations as exact/verbatim. No source correction or code change was made; this is a focused quality follow-up, not a reason to repeat paid processing automatically.

Browser Review extracted details successfully loaded extraction66, complete semantic booking/facts and expanded lifecycle evidence. The existing frontend renders V3 review read-only with corrections/confirmation disabled by its existing activation gate. This test establishes review access, not correction/confirmation/Reservation/AskAI acceptance.

### Final safety/accounting

Mapping Disabled; source visible/in-flight/delayed0/0/0; historical DLQ3/0/0 unchanged and never received/redriven/deleted. No active attempt, pending outbox or queued/recoverable job. Document53 REVIEW_REQUIRED, attempt/delivery1, no failure/error. Exactly+1 travel_document,+1 outbox,+1 extraction; fingerprints excluding document53 and its dependent rows match all original processing rows. All other15-table baseline fingerprints unchanged. Historical documents7 and52 preserved. No duplicate extraction, invocation, paid attempt or stale completion observed. The published outbox35 remains as normal audit history, not pending work.

**MAX_DELIVERIES remains1 in both runtimes. No restoration performed.** Test message was acknowledged and is fully accounted for; any later restoration still follows the recorded explicit restoration procedure/approval. SQS remains disabled. No timeout/model/schema/code fixes were implemented after the test.

Evidence: build/controlled-e2e-20261025/{expected,preflight,browser-timings,lambda-logs,test-latest,extraction,timing-summary,correctness,accounting,queues-final,mapping-final}.json. Operational monitor auto-disabled on terminal state and had a240s failsafe; it used only read-only PostgreSQL plus the authorized enable/disable of the existing mapping.

Smallest next action: review the two composite quotation entries; preserve this successful run as the reliability baseline. Cold startup20.75s and provider extraction33.00s remain the dominant measured latency. No automatic rerun, optimization or five-attempt restoration.


## Local evidence-grounding correction — 2026-10-25

Root cause: SemanticTourSchemaV3 allowed combining contextual evidence but did not explicitly forbid stitching separated source passages into one quotation. SemanticTourV3Codec strictly decoded the JSON; SemanticTourExtractionV3Validator checked shape, nonempty quotations, positive optional pages, booking association, semantic relationships and arithmetic, not quotation presence in original bytes. Trusted sourceSha256 bound the document identity, not the accuracy of each model-authored quotation. Consequently extraction66's date/end snippets passed even though the source has a start-time row between them.

Implemented locally, not deployed:

- Added org.apache.pdfbox:pdfbox:3.0.8 to build.gradle. PDFBox's Loader/PDFTextStripper reads page text from the same cloned PDF byte snapshot sent to OpenAI. No external OCR, network request, new service or persistence is involved. API reference: https://pdfbox.apache.org/3.0/migration.html.
- Added ai/document/SemanticTourSourceEvidence.java. It normalizes only Unicode canonical composition (NFC) and Unicode whitespace. Each quotation must remain a contiguous substring of its reported page. If page is unknown, one complete page must contain the quotation; matching never joins pages. Case, punctuation, numbers, hyphens and all intervening words remain significant. Errors omit quotation text and parser details.
- OpenAiAttractionTourDocumentExtractor.extractSemanticV3 checks that readable source text exists before the extraction request, then checks every evidence list after existing strict decoding/structural validation and before returning to the normal worker/finalizer. Coverage includes core fields, conditions, temporal constraints, lifecycle, replacement relationships and supplemental facts. Bad evidence raises the existing InvalidDocumentExtractionException; existing invalid-extraction failure/attempt policies remain unchanged. No repair, splitting, rewriting, second model call or additional retry is performed by the extractor.
- Clarified SemanticTourSchemaV3 instructions: one contiguous passage per quotation; separated passages use separate entries in the existing evidence array. Schema, prompt identity, model settings, typed facts and interpretation rules are unchanged.

Compatibility/limits: historical stored reads use their existing structural reader and remain readable; no source-grounding check is retroactively imposed on confirmation/review reads and no stored JSON is rewritten. Extraction66 and AWS/document data were not accessed or mutated in this task; its already-saved local synthetic replay artifact was copied into new local regression fixtures. This is literal-text presence checking, not proof of semantic entailment, authenticity, correct booking interpretation or visually faithful PDF reading order. Complex layouts/text encodings can fail conservatively. Image-only/scanned/unreadable/encrypted PDFs and JPEG/PNG inputs cannot be independently grounded without OCR; new V3.4 extraction fails closed for them before making its extraction request. A normal worker may already have classified an unknown-category input before reaching this boundary. No OCR or alternative pipeline was introduced. Legacy/V2 extraction and historical reads are unchanged. This input-support limitation must be reviewed before deployment; do not describe the implementation as OCR-capable.

Verification: Java production compilation succeeded. Final focused offline Gradle run passed **127 tests, zero failures/errors/skips**, across SemanticTourSourceEvidenceTest(11), DocumentContractWorkerIntegrationTest(28), SemanticTourV3ContractTest(15), SemanticLifecycleV34ContractTest(9), SemanticRolesV3ContractTest(12), SemanticStatusV33ContractTest(6), SemanticTemporalConstraintV3Test(6), TourV34CompactJsonCandidateTest(4), TourV34ReasoningCandidateTest(5), SemanticConfirmationWorkflowTest(21), SemanticV3ConfirmationPolicyTest(5), SemanticV3ReviewTest(5). Providers are mocked; integration uses H2, not persistent databases. Log: /private/tmp/v34-evidence-tests-final.log. Whitespace checks passed.

The original local extraction66 replay is still structurally readable and is rejected by the new source check. A local simulated NEW response replaces only its two joined evidence entries with separate Date and End time passages; it passes full decoding, existing validation and new grounding, preserving booking values, lifecycle, supplemental information and provenance. Additional regressions cover formatting whitespace/NFC, independent page references, omitted/reordered text, ellipses, invented/missing quotes, changed numbers/case/punctuation, incorrect pages, unknown pages, cross-page joins, conditions/replacement evidence and failures before a paid request for unreadable inputs. The actual extractor/worker test proves fabricated evidence cannot create an extraction row or reach REVIEW_REQUIRED and clears ownership through the existing terminal failure path at max1.

Existing adapter/candidate tests used fake non-PDF bytes; they now use generated, valid local PDF sources for their mocked evidence. The no-provider-at-persistence-reserve test likewise uses readable PDF bytes. One stale roles-test assertion expected the historical literal tour-semantic-3.1 in the current instructions (already absent before this change); it now checks the current schemaVersion3 declaration instead. No production instruction was added merely to satisfy that assertion. Test helpers/fixtures remain local and unstaged.

No live test is needed to prove deterministic rejection of the known defect. A separately approved post-deployment controlled request is still needed to measure the model's compliance with the clarified instruction and confirm real end-to-end acceptance; offline tests cannot establish that compliance rate. No deployment, AWS operation, persistent DB change, paid provider request or Git staging/commit occurred. Both prior optimization candidates and all runtime settings are unchanged.


## Evidence input-compatibility correction — local only, 2026-10-25

This section supersedes the preceding local correction's scan/image fail-closed policy. That policy introduced an input-support regression and is removed; it was never deployed.

Verified cause: SemanticTourSourceEvidence.read rejected non-PDF content and empty PDF text before the existing provider request. Its page matcher also rejected every quote on a scan-only page. Normal processing already accepts PDF/JPEG/PNG; the extractor sends PDFs as input_file and JPEG/PNG as input_image/AUTO, then uses the same strict response decoder and V3.4 structural/semantic validator. The extra source-text gate incorrectly made PDFBox readability an extraction prerequisite.

Acceptance tests were added before production changes. The three targeted regressions failed against the previous implementation: four-input adapter compatibility, mixed text/scan PDF, and scanned body with a text header. Log: /private/tmp/v34-input-compatibility-before.log (3 tests,3 failures).

Correction scope: only SemanticTourSourceEvidence.java, the existing evidence-check comment/logging in OpenAiAttractionTourDocumentExtractor.java, SemanticTourSourceEvidenceTest.java and this record. No schema, model, prompt, provider input format, correction/confirmation, persistence or worker lifecycle changes in this correction. The previous PDFBox dependency remains.

Source capabilities are page-local. A readable text page without raster content requires the exact contiguous normalized quotation on that page; fabrication, omitted intervening text, reordering, incorrect pages and joined passages still fail. Only NFC/whitespace normalization is used. Scanned pages, JPEG/PNG and unavailable PDF parsing retain the existing provider/decoder/V3.4 validation path; lack of deterministic source text is not an input rejection. Scanned bodies with small text headers remain partially inspectable, including raster images in nested forms and inline images. A page-specific read failure does not remove checks on other readable pages. Source text that actually matches is counted as TEXT_MATCHED; evidence not deterministically checkable is UNVERIFIED. Known page bounds still apply.

| Input | Existing extraction | Quotation handling |
| --- | --- | --- |
| Text PDF | Preserved | Strict contiguous match on the reported readable page |
| Scanned PDF | Preserved | AI-reported, unverified where text is unavailable |
| JPEG | Preserved | AI-reported, unverified; one source image/page |
| PNG | Preserved | AI-reported, unverified; one source image/page |
| Mixed PDF | Preserved | Strict on known readable pages; unverified on scanned/partial pages |

No evidence is rewritten, repaired, silently split or marked globally verified. Counts are sanitized diagnostic logging only (`textMatchedQuotations`, `unverifiedQuotations`); no new serialized fields or persisted verification claims are introduced. The existing review/confirmation contracts continue to describe evidence as AI-reported. Historical source JSON/extraction66 is unchanged.

Limitations are explicit: no OCR and no claim that text-layer presence proves visual fidelity, interpretation accuracy or authenticity. Raster-containing pages are conservatively partial, even when they also contain text (including a small logo); matching available text can be checked, but unmatched raster-page wording remains unverified. For unknown-page evidence in a mixed PDF, an unmatched quotation cannot be conclusively attributed to a readable page and remains unverified. Whole-document PDF parsing failure similarly cannot establish page count/text. This fallback does not certify malformed PDFs as valid: provider failure and existing strict response validation remain authoritative. A mocked local parser-failure test checks compatibility routing, not real provider acceptance of invalid bytes.

Final local verification: **133 tests passed; zero failures/errors/skips**, including17 evidence tests and116 adapter, worker, lifecycle, temporal, uncertainty/applicability, review and confirmation regressions. Tests use real synthetic text PDF bytes, rasterized scans, valid JPEG/PNG encodings, mixed PDFs and an inline-image/text-header case. Each four-input case traverses the real OpenAiAttractionTourDocumentExtractor request construction, unchanged decoder, V3.4 validator and evidence checker with exactly one mocked response; the bookings/supplemental facts remain equal and source hashes bind each respective input. Mixed-page tests assert both matched and unverified counts and reject a fabricated quote explicitly attributed to the readable page. Images still reject structurally invalid/empty evidence. The original two joined quotes in the readable benchmark remain rejected. Production/test compilation and complete scoped diff/whitespace review passed. Log: /private/tmp/v34-input-compatibility-final.log.

LOCAL CANDIDATE READY for separately authorized live acceptance, not a live-resolution claim. Offline fixtures cannot establish real vision extraction quality or real mixed-layout coverage. No AWS operations, deployment, persistent DB change, OpenAI call, Git staging or commit occurred. No new infrastructure, OCR, model, schema or additional provider requests were introduced. Existing unrelated uncommitted work and the persistent tunnel were not altered.


## Corrected evidence-validation release attempt — 2026-10-25

**BLOCKED at artifact transfer; corrected code was NOT deployed.** No Lambda UpdateFunctionCode request, API deployment, configuration/IAM/queue change, migration, document operation or paid provider request occurred. SQS stayed disabled throughout.

Exact source comparison against the frozen deployed reliability release found only build.gradle(PDFBox3.0.8), OpenAiAttractionTourDocumentExtractor, SemanticTourSchemaV3's contiguous-quotation instruction, and new SemanticTourSourceEvidence. Removing only these additions in memory reconstructs each changed baseline file's original SHA256; every other recorded source hash matches. Compiled comparison found the two intended changed classes, four new helper/nested classes, and a regenerated extractor correction-result record with identical signatures/executable bytecode (compiler metadata only). Existing dependency JAR bytes are identical; additions are pdfbox3.0.8, pdfbox-io3.0.8 and fontbox3.0.8. No shared schema/domain/validator/persistence/finalization/confirmation/receipt/lease/retry classes changed. Full release source diff is preserved; no selective source overlay was used.

Lambda-only scope was verified: the API's AWS-mode Process endpoint queues jobs; V3.4 extraction runs through the Lambda worker. The API's correction-assessment method/semantics and stored-extraction reader are unchanged. API revision29 and frontend therefore need no redeployment for this correction. Fresh normal bootJar/plain JAR/lambdaZip builds succeeded. Three focused offline checks passed: actual full worker component scan with H2 create-then-validate bootstrap, normal API wiring and protected-route/readiness compatibility. The unchanged corrected source had already passed133 focused extraction/format/lifecycle/uncertainty/review/confirmation tests. Fresh API/Lambda application/shared-library bytes match, with the same existing12 Lambda-only starter-JAR packaging difference. ZIP uncompressed size is within Lambda's limit.

Candidate artifacts in build/evidence-validation-release-20261025/artifacts:

- Lambda ZIP: SHA2564865df6ab10fcd822f82f4e8094094345c6c8e654f5af363be48f1402bef4c05;143469692bytes; AWS CodeSha256 SGXfarEPzYIvgvToCUCUNFxsjmVPWvNjvkjxQCvvTAU=.
- Plain application JAR: SHA256e2c1084d3c539fa8eb952e70a228681857918d5bd8536222bae98de4b71a9e11.
- API compatibility build (not deployed): SHA256cdf3fd850b15ee19d5b0bc67c20b6c7f540d08818d120754937f9f60ce61bb0d.

Both immutable PutObject attempts to the existing artifact bucket/key failed with `Connection was closed before we received a valid response from endpoint URL`. Read-only HeadObject returned404 after each, so no complete object was available to verify/use. One intervening fresh PostgreSQL safety check timed out through the established VPC/SSM path; a single retry passed before the second upload. No alternate upload path or further retry was improvised. Upload connectivity is the concrete blocker; the underlying reason for the connection closure is not established. This is not evidence of an application, credential, schema or extraction defect.

Final preservation verification: existing Lambda revision590adf88-6e98-4ccf-9d6b-db0554586dcc remains Active/Successful with original ZIP SHA2563c7bf3a391d5515d68305d07786c13803b8f7dbd3f622d61059de37ab490e933 (CodeSha256 PHvzo5HVUV1oMF0HeGwTgDuPfb0/Yi1hBZ3jerSQ6TM=), and its entire configuration equals the preflight snapshot. API revision29 remains the same healthy task. Frontend-proxied readiness200/UP. Timeout120, lease180 in both, MAX_DELIVERIES1 in both, source visibility720, batch1/window0 preserved. Mapping Disabled; source0/0/0; DLQ3/0/0 unchanged. All27 public-table counts/fingerprints match before/after, preserving documents7/52/53, extraction66, receipts, users and conversations; no pending/active/recoverable work was found. No processing was started. Existing frontend/tunnel processes were not restarted.

Prior deployed ZIP/config and immutable artifact reference remain available for rollback, which was unnecessary because code never changed. Frozen candidate/source diff/manifests, preflight snapshots, upload-attempt count, interrupted-upload verification and full data-preservation fingerprints are in build/evidence-validation-release-20261025. release-manifest.json explicitly records BLOCKED_ARTIFACT_UPLOAD_NOT_DEPLOYED. No Git staging/commit occurred.

Next action: restore reliable transfer to the existing S3 artifact endpoint, then resume immutable artifact publication and the approved Lambda-only update from this exact frozen ZIP after fresh safety checks. No rebuild, schema/IAM/runtime setting change is required. Format compatibility remains offline-verified only; actual processing acceptance requires separately approved live tests after deployment and deliberate SQS activation.


## Evidence-validation deployment resumed — 2026-10-26 local time

**Lambda deployment complete; AWS preservation checks passed. Local frontend readiness remains blocked by the existing SSM connection.** This supersedes the previous artifact-transfer blocker, while preserving its two failed attempts and diagnostics.

The frozen ZIP was not rebuilt or modified: `build/evidence-validation-release-20261025/artifacts/document-processing-lambda.zip`, 143,469,692 bytes, SHA256 `4865df6ab10fcd822f82f4e8094094345c6c8e654f5af363be48f1402bef4c05`. The earlier failures were connection termination during single-request `s3api put-object`, followed by HEAD404, not a recorded credential or permission denial. AWS CLI2.36.40 uses the correct profile/region with no custom endpoint or proxy environment. Read-only GetBucketLocation confirmed the approved bucket is in us-east-1. The exact actor/network cause of those previous closures remains unestablished.

One bounded resumed upload used the existing integrated-release `aws s3 cp` multipart mechanism, with AWS_MAX_ATTEMPTS=1, 15-second connect timeout, 90-second read timeout and a 600-second parent limit. It succeeded to the same approved bucket `trip-coordinator-dev-artifacts-ab757f1e6ad394d9dbb56fc1d2`, key `document-processing/releases/evidence-4865df6ab10fcd822f82f4e8094094345c6c8e654f5af363be48f1402bef4c05.zip`. HEAD confirmed size; a full read-back download matched the frozen ZIP SHA256 and byte count. S3 returned a COMPOSITE SHA256, which was not treated as the full-file hash; neither metadata nor multipart ETag was used as proof of artifact integrity. Only the disposable verification download was removed afterward. No bucket, endpoint, IAM or credential configuration was changed.

UpdateFunctionCode was fenced against expected Lambda revision `590adf88-6e98-4ccf-9d6b-db0554586dcc`. Final function `trip-coordinator-dev-document-processing` is Active/Successful at revision `6beb2032-da8b-48ad-81ad-48051a259363`, CodeSha256 `SGXfarEPzYIvgvToCUCUNFxsjmVPWvNjvkjxQCvvTAU=`. No version was published and no function invocation was submitted. All Lambda configuration, IAM policy and mapping settings compare unchanged. API revision29 remains the same HEALTHY task; no API/frontend rebuild or deployment occurred.

Verified preserved settings: Lambda timeout120 seconds; API/Lambda worker lease180 seconds and MAX_DELIVERIES1; source visibility720 seconds; batch size1/window0. SQS mapping remains Disabled; source visible/in-flight/delayed0/0/0; DLQ3/0/0, with no receive/delete/redrive operation. No pending, active or recoverable processing work was found. All27 public-table counts/fingerprints match the fresh preflight, including original documents/extractions, users, identities, reservations, receipts and conversations. One post-update PostgreSQL fingerprint connection timed out through SSM; a single fresh existing read-only session using one SQL connection completed the full safety/fingerprint verification. CloudWatch release-window inspection found no Lambda invocation START. No document processing, paid provider call or database mutation occurred.

The local readiness path did not pass: the existing localhost8080 listener first timed out, then closed without an HTTP response; the localhost5173 frontend proxy returned500. This is recorded separately from AWS task health and successful Lambda deployment. The preexisting user tunnel/frontend processes were not stopped or replaced. The smallest remaining browser-access action is to restore the established ECS/SSM port-forward and recheck direct/proxied readiness; no code rollback or redeployment is indicated by these observations.

The previous deployed ZIP (SHA256 `3c7bf3a391d5515d68305d07786c13803b8f7dbd3f622d61059de37ab490e933`), its existing S3 reference and runtime snapshots remain preserved for separately approved revision-fenced rollback. No rollback was used. Release manifest and resumed evidence are at `build/evidence-validation-release-20261025/release-manifest.json` and `resume-20261026/`; final status explicitly separates successful AWS deployment from blocked local HTTP verification. No Git staging/commit occurred. Real PDF/scanned/JPEG/PNG/mixed-format acceptance remains unverified live and requires separate processing authorization; SQS stays disabled.


## Document54 controlled recovery — 2026-10-26 local time

**Processing and live review succeeded; the requested ACTIVE-lifecycle acceptance check did not pass. Normal-operation restoration was not performed under the conditional authorization.** The model returned AMBIGUOUS with ACTIVE/UNKNOWN alternatives, citing both `CONFIRMED - ACTIVE` and `SYNTHETIC TEST BOOKING - NOT VALID FOR ADMISSION OR TRAVEL.` This is not a provider timeout, validation rejection or failed worker transaction. The source truly contains both passages. No fact, validator, prompt or immutable result was changed to force acceptance.

Before activation, document54/trip7 (`paris_booking_01_montmartre_walk.pdf`), job `ac0cb8e7-f426-46b3-a138-181dd251b8cf`, outbox36 PUBLISHED/AUTO_V3_4, was the sole eligible work, attempts/deliveries0. Source queue1/0/0, original DLQ3/0/0, no pending outbox or other active/recoverable job. Historical document7 had no job/claim/start and was not eligible. ECS29 and corrected Lambda revision `6beb2032-da8b-48ad-81ad-48051a259363` were healthy with MAX_DELIVERIES1, timeout120, lease180, queue720 and batch1/window0. The original S3 PDF was downloaded read-only for verification (SHA256 `3ba6a1dcfc8d9a4b79e553f382e78e1ee844cf850f8350696786dcfa8032a2c3`,109026bytes). It was not uploaded, requeued or modified.

Existing mapping activation began2026-10-25T21:46:24.446059Z. Exactly one cold Lambda invocation `0b65e617-0565-59b3-9659-854f7f9366f7` ran21:46:32.841Z–21:47:42.748Z. Claim at21:46:58.104Z, receiveCount1/CLAIMED. Existing contract resolution persisted SEMANTIC_V3_4. Document reached REVIEW_REQUIRED with exactly extraction67 (created21:47:42.688251Z), one attempt/delivery, no last error or active claim. SQS consumption was paused for verification; source message was successfully acknowledged and queue drained. No additional processing attempt or benchmark was submitted.

Measured durations: Lambda platform init0.82318s; worker context startup22.552s (Spring reports21.574s); storage0.284s; classification stage5.695s/provider5.521s; extraction-plus-validation38.098s/provider35.737s; finalization0.195s; Lambda invocation69.90629s. Activation-to-persisted result78.242192s. Standalone validation is not separately timed; subtracting provider duration also includes request preparation/other work and is not a pure validation measurement. The earlier upload-to-result interval includes approximately2h49m of disabled-consumer waiting and must not be presented as AI processing time.

One classification and one extraction completion were logged, zero SDK retries, no additional Lambda invocation in the inspected window. Extraction usage:5820 input,5987 output (717 reasoning,5270 non-reasoning). Classification token usage is not logged. Deployed grounding log reported33 text-matched quotations and0 unverified; an independent normalized contiguous comparison of all33 against page1 also passed. Source SHA, booking associations, referencePAR-MON-31047, providerExample Paris City Tours,30October2026 10:00–12:30 Europe/Paris,09:45 arrival, Place des Abbesses meeting and Sacre-Coeur ending/main-steps address match. Show-confirmation/shoes requirements, late-arrival notice, inclusions/exclusions, and free cancellation until29October10:00CET/non-refundable afterward are preserved. Required ACTIVE lifecycle is the sole failed core acceptance check; its alternatives/uncertainty are preserved.

Actual browser verification: existing Cognito sign-in succeeded at localhost5173, six original trips loaded, Paris E2E Test opened, Travel Documents showed Review required, and Review extracted details rendered extraction67, policies, facts, lifecycle ambiguity and the existing read-only confirmation/correction gate. No Reprocess/Retry/confirmation/correction was clicked. No tunnel restart was needed in this recovery. This verifies the real review screen, not only health checks.

Final read-only accounting: source0/0/0; original DLQ3/0/0 untouched; no pending outbox, active or recoverable work; exactly one extraction for54. All27 original public-table counts/fingerprints excluding only document54's processing rows/extraction match the preflight. No other document, reservation, receipt, account or conversation changed. API29 HEALTHY; Lambda Active/Successful with same code/config. MAX_DELIVERIES remains1 in both; mapping Disabled. No API/Lambda configuration revision was made, no new artifact or application code was deployed, no database status was manually manipulated, no DLQ receive/delete/redrive occurred.

The recovery's conditional final step (both caps5 and normal continuously Enabled consumption) is intentionally incomplete because ACTIVE-lifecycle verification failed. Next decision is to review/accept the synthetic-document ambiguity before authorizing that restoration; do not silently rerun extraction or modify the validator. Once the result is accepted, the already-empty/accounted queue permits a configuration-only restoration to5 in both runtimes and final Enabled mapping. Evidence and exact operational state: `build/document54-recovery-20261026/recovery-report.json`, processing snapshots, source, CloudWatch log capture and final queue/runtime/accounting files. No Git staging/commit occurred.


## Normal AWS-DEV processing restored — 2026-10-26 local time

Completed under separate operational approval, verified2026-10-25T23:21:21.740166Z. Fresh independent preflight confirmed the correct account393450686123/us-east-1, API29 and corrected Lambda healthy, mapping Disabled, source0/0/0, original DLQ3/0/0, no pending outbox/active/recoverable processing, and document54 REVIEW_REQUIRED with one attempt/delivery and exactly extraction67. All27 public tables were fingerprinted before changes.

Only MAX_DELIVERIES changed from1 to5 in each runtime, with consumption disabled throughout configuration. API configuration-only task revision30 uses the identical deployed image and other task settings, and reached HEALTHY/stable before the Lambda update. Lambda configuration update was fenced by its prior revision; final revision `45cd2a07-cb93-4227-b1a9-5ec22caa4cf1` is Active/Successful. Its code checksum remains `SGXfarEPzYIvgvToCUCUNFxsjmVPWvNjvkjxQCvvTAU=`. Exact configuration comparisons passed. Timeout120s, API/Lambda lease180s, SQS visibility720s, batch1/window0 and all other runtime settings were preserved. No application source, artifact, IAM, queue property or database change was made.

After another read-only idle-work/fingerprint check, existing mapping `5ab00c97-51d0-4ec0-af22-6269c2cb2c8c` was enabled and confirmed still Enabled after final verification. Final source0/0/0 and original DLQ3/0/0; no pending/active/recoverable work; no Lambda invocation START observed in the restoration window. All27 table counts/fingerprints remain identical. Document54/extraction67 were not reprocessed or altered; lifecycle ambiguity remains preserved. No OpenAI request, upload, requeue, DLQ receive/redrive/delete or manual status mutation occurred.

Direct localhost8080 and frontend-proxied localhost5173 readiness both return200/UP. The initial forwarding path lost its retired API29 target. A guarded reconnect attempt found that the listener had already been replaced, so it stopped without terminating any process or starting another tunnel; the existing replacement was left untouched and passed both readiness checks. Other services were preserved.

**Final normal state: API30 HEALTHY; Lambda Active/Successful; MAX_DELIVERIES5 in both; SQS Enabled and left Enabled.** This operational restoration supersedes the previous temporary disabled-consumer/cap1 hold; it does not change or certify the synthetic fixture's lifecycle interpretation. Baseline snapshots, exact proposed/configured changes, final fingerprints, queue/mapping snapshots, no-invocation log inspection and HTTP checks: `build/normal-processing-restoration-20261026/restoration-report.json` and adjacent evidence. No Git staging/commit occurred.

## Local-only reliability budget revision — 2026-10-29 (NOT ACTIVATED)

This supersedes earlier proposed values for a future coordinated release only. No AWS settings, running process, database or provider was changed. This is timeout/reliability headroom, not an extraction-latency fix.

- Lambda 180s; classification cap20s; extraction cap90s; provider request remains min(phase cap, live remaining invocation time minus30s). Connect/write caps stay5s/10s, SDK retries0. The30s persistence reserve, bounded10s transactions/DB statements and independent failure commit remain unchanged.
- API/Lambda lease240s; SQS base visibility1080s (6*180, batch1/window0). Terraform API validation/injected environment and application/service defaults agree. No previous240s Lambda/300s lease/1440s visibility bundle is adopted.
- Existing per-receipt startup guard becomes remaining lifetime+60s (up to240s); claimed deliveries remain protected to committed lease expiry+30s (about270s after claim). Controlled committed failures retain15/30/60/120s visibility changes; Lambda failure signaling, visibility-failure behavior and terminal/DLQ handling are unchanged. The pending extraction-timeout cap makes the second committed provider-tagged extraction timeout terminal, so ordinary extraction timeouts use only the first15s automatic retry. Abrupt termination is separate and not invented as a provider timeout.
- With measured26.440s startup and worst-case20s classification,90s extraction leaves43.560s total, including30s persistence and13.560s other overhead. This is arithmetic, not a measured guarantee. A45s planning startup with20s classification clips extraction to85s; slower startup/other work reduces it further. A response completing in73s can still be semantically invalid.
- Full V3.4 model schema now branches condition by kind: numeric noticeMinutes only for CANCELLATION; minimumAgeYears only for PARTICIPATION. Other timing stays in existing descriptions/qualifications/details. Value and alternative references share the constraint. No rewriting, dropped fields, model switch, contract identity change or relaxed semantic/quotation validation. The local compact schema inherits the same condition constraint but is not activated; canonical remains isolated under build/.

### Coordinated release requirements — separately approved later

1. Freeze scoped API/Lambda artifacts from the reviewed baseline; exclude unpublished compact/canonical contracts and unrelated Ask AI/frontend work. Recompile the extractor with the intended full schema/instructions, include budget/claim/recovery defaults and generated classes, plus the existing pending timeout-cap entity/repository/failure-policy changes. No new runtime dependency is required.
2. Preserve compatible rollback artifacts and take a verified backup. Pause ingress/consumption and drain all active/recoverable work; a retry must not switch request semantics mid-job. Review/apply ONLY the pending `db/manual/2026-10-28-document-extraction-timeout-outcome.sql` under separate approval. Do not apply the unrelated compact-contract migration. No migration has been applied here; native PostgreSQL execution remains to be verified.
3. With consumption disabled, align API lease240 and compatible counter/reset behavior first; update source visibility1080, then install compatible Lambda with timeout180/lease240. Retain queue URL, source-only ChangeMessageVisibility IAM, batch1/window0, profile, zero SDK retries and existing failure/recovery policies. Both runtimes must use matching lease and counter policy before processing resumes. Broader Terraform plans must be inspected for unrelated local changes; do not apply the full working tree blindly.
4. Deploy the existing timeout-specific frontend message as a separately scoped matching artifact. Verify health, exact artifacts, schema, configuration, idle state and preserved data before any separately approved processing. A controlled evaluation should use at most one paid attempt. None is authorized or started by this local task.
5. After a nonzero timeout counter/new outcome exists, rollback must retain counter/reset/error-code compatibility; an unchanged old API may violate the counter constraint when resetting failures. Keep schema/data in place and use compatible rollback/forward-fix. Do not revert to shorter lease while a longer-lease attempt or receipt remains active. Preserve existing historical failed documents; no backfill/requeue.

### Verification and limits

Focused offline results are under `build/worker-budget-90-20261029/`. Budget, HTTP-mock deadline/no-retry, SQS-mock receipt/retry failure signaling, selected H2 lease/concurrency/stale-finalization, timeout-cap persistence and existing V3 contract checks were run; semantic/quotation validator hashes and pending timeout-cap/migration hashes are unchanged. Actual generated full and inherited compact schemas pass offline strict-structure checks; the saved invalid document57 response is rejected while the explicitly manual valid expected fixture passes. This is not provider schema acceptance, real-model accuracy, latency, native PostgreSQL migration validation or live AWS recovery evidence. Unknown/lost failure commits and crashes are not an exactly-once billing guarantee. Provider-schema first-use overhead and incorrect semantic classification still require controlled evaluation; a structurally valid cancellation label can still be wrong against the source.

## 2026-10-30 deployed timeout recovery release

API revision44, immutable image `sha256:9f70a5b863f4d0a959a0d98056b90306d28087637516442b7e3ab2592c46fb48`, frozen JAR SHA256 `c254ed54fe2821e2f9054822015cfd61d799ee5d219e5fa773ac3096b9575bc3`. Lambda `$LATEST` revision `74f3926e-e79c-4705-9308-c9cb011080dc`, ZIP SHA256 `b37de4855326d7106105b140293589095d698fe0b91e429836ca0aa545acbf34`, AWS CodeSha256 `s33khVMm1xBhBbFAKTWJCV1pj+C5HkKYNsoKpUWsvzQ=`, Active/Successful. Exact11 entry changes in each artifact; provider/extractor/schema/validator classes and all dependencies unchanged. No provisioned bootstrap, Fast tier, experimental contract or pending instruction change included.

Fresh PostgreSQL16 custom backup verified by archive contents and complete archive read, SHA256 `7d6c238bbccf6bc2471da02a0f6025347d92b770b09f64541820d41ecec3bac9`. Only frozen migration.sql (`b1aa17a4526f089f49799edc6546bfdb5a5ced6e2ee1811ddbaf654eb41ae72f`) applied once. Its column/default/constraints validated; counters0 and pre-existing document/extraction/outbox/reservation/receipt count/hash fingerprints unchanged. A network failure interrupted only the post-check; read-only reconciliation completed without reapplying SQL.

Actual: extraction maximum90s clipped to live remaining invocation minus30s; classification20s; Lambda180s; matching API/Lambda lease240s; visibility1080s; max deliveries5; batch1/window0. First committed extraction-provider timeout permits one automatic retry; second terminal FAILED/EXTRACTION_TIMEOUT; explicit new-job counter reset is atomic. Generic failures, receipt ownership/fencing, normal retry/DLQ handling unchanged. Crashes/failure-persistence loss are not counted as committed provider timeouts and are not an exactly-two-billed-calls guarantee.

API healthy/stable, no old readers. Direct/proxied readiness200/UP and protected unauthenticated `/api/me`401. Reused existing tunnel/preview; existing served frontend has both timeout messages; no frontend release. Existing mapping restored Enabled on its original unqualified function; source0/0/0, DLQ4/0/0 untouched. No processing/requeue/AI request submitted. CloudWatch observation is recorded separately with ingestion-delay limitation.

Infrastructure records now name the frozen Lambda ZIP and immutable API release tag plus lease240; existing Terraform timeout/lease and six-times visibility expressions match180/240/1080. No broad Terraform apply, IAM, alias, provisioned-capacity or quota change. Other local work preserved.

Counter-compatible rollback image `393450686123.dkr.ecr.us-east-1.amazonaws.com/trip-coordinator-dev-api@sha256:2950d770f98a56bc54ca5cbd773dfec8479b0ae9627e6dd24525860843d7d23e` and matched worker are published and preserved; see ROLLBACK.md. Do not restore unchanged43 after counters become nonzero. Retain schema/counters, pause and fully drain work before reverting matched120/180/720 settings, otherwise contain/forward-fix on compatible readers. Backup is recovery evidence, not permission to overwrite newer application data.

This is temporary completion/retry headroom, not a latency or extraction-correctness fix. No real-model/normal-processing acceptance was performed; existing semantic/quotation validation remains mandatory.

Frozen manifest and operational records: `build/timeout-recovery-90-release-20261029/`.
