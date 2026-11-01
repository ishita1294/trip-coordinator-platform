# AWS-DEV startup-only Lambda release

Prepared 2026-10-24. **READY for release review; not deployed.** No AWS configuration, queue, IAM, database or deployed code was changed.

## Exact baseline

Read-only `GetFunction` identified the current function `trip-coordinator-dev-document-processing`, `$LATEST`, revision ID `c2560553-3d56-49c1-b39d-311bb609cc15`, last modified `2026-10-22T02:26:16.000+0000`.

The downloaded ZIP has SHA256 `5b42a1899d71656fefa84207c63c9283eb06d5ac74eade22826c82f4c0796036`, exactly matching the live `CodeSha256` and the integrated revision-27 release record. The existing handler, Java25/x86_64 runtime, 1024 MB memory, 60-second timeout, role and environment remain the deployment baseline. The live SQS mapping remains Disabled. The queue's 180-second visibility is unchanged; no queue messages were received, replayed, deleted or modified.

## Reproducible source and release scope

Local release directory: `build/startup-only-lambda-release/`.

Seven baseline source files were recovered by removing only the already implemented `document-worker` import/annotation from their current copies. Every recovered file's SHA256 exactly matches the original integrated source manifest. The baseline handler was recovered from the existing frozen source snapshot plus its existing disabled-local-execution initializer; the reconstructed complete file also exactly matches the integrated source manifest. No decompilation or bytecode rewriting was used.

`prepare_sources.py` records and verifies that source chain. Only isolated release sources are compiled; the working-tree implementation is untouched. `package_release.py` starts with the exact downloaded deployed ZIP and replaces the reviewed startup class entries. The ZIP's other entries and application resources remain byte-for-byte identical in content. All 175 dependency JARs are unchanged. Repeated packaging produced the same checksum.

Exact source patch: `startup-only.patch`; source hashes: `source-manifest.json`; entry-level binary hashes: `binary-diff.json`; dependency hashes: `dependency-manifest.json`.

Changed source paths relative to the application package:

1. `lambda/DocumentProcessingLambdaHandler.java`: programmatically enable the existing `document-worker` profile, attach the existing Spring startup-stage listener, and time aggregate secret loading. No delivery or provider logic changes.
2. `config/AIConversationCleanup.java`: exclude from the worker profile.
3. `intelligence/explore/ExploreService.java`: exclude from the worker profile.
4. `intelligence/explore/ExploreScenarios.java`: exclude from the worker profile.
5. `intelligence/explore/ExploreRestPlanning.java`: exclude from the worker profile.
6. `intelligence/explore/ExploreRequestGuard.java`: exclude from the worker profile.
7. `intelligence/explore/FeasibilityEngine.java`: exclude from the worker profile.
8. `intelligence/explore/GroundedExploreRanker.java`: exclude from the worker profile.

The binary release changes exactly nine entries: those eight outer classes and the new private `DocumentProcessingLambdaHandler$StartupTimings.class`. Existing nested helpers are retained from the baseline. No extractor, validator, schema, model, prompt, confirmation, ownership, claim, failure, finalization or retry class changes. `application.properties` is unchanged.

Per-secret `RuntimeSecrets` instrumentation remains in the bundled work and is not included here. The startup-only release records aggregate secret loading and the Spring startup phases without modifying the deployed secret loader.

## Compatibility and checks

- All 505 baseline application classes/configuration entries compared with the existing revision-27 API artifact matched exactly.
- ASM executable-method comparison: all seven profile-only classes retain every method body/signature; the handler changes only `application()` and `startContext()`. Message parsing, handling, ownership decisions, delivery limits and failure signaling are unchanged. See `bytecode-compatibility.json`.
- Packaged worker retains the baseline **120-second lease**, five-attempt policy, existing provider behavior and existing SQS handling. It contains no `DocumentProcessingBudget` or `DocumentProcessingVisibility` classes and needs neither a queue-URL environment variable nor new IAM permission.
- Existing API profile still satisfies all seven `!document-worker` conditions. No API artifact or ECS deployment change is needed.
- **37 focused checks/assertions passed** against the actual candidate ZIP's classes and dependencies. This includes a complete non-web Spring component scan, H2 fixture preparation followed by validate-only startup, essential worker/extractor/finalizer availability, exclusion of the seven unrelated beans, absence of web identity/filter/controllers/publishers/coordinator, API profile conditions, and mocked delivery/failure/duplicate/active-attempt/malformed-message/five-delivery-limit checks.
- The first harness run exposed duplicate baseline application resources on its test classpath; the harness was corrected to run from the candidate ZIP contents only. The successful result is recorded in `offline-checks.json` and `offline-checks.log`.
- The formerly skipped PostgreSQL-specific bootstrap test was not run: it creates/drops a PostgreSQL schema. Its full-context startup objective was instead exercised using the existing in-memory H2 create-then-validate fixture pattern. No persistent or AWS database was accessed. PostgreSQL-specific startup behavior and real AWS startup latency are not newly measured.
- Final read-only AWS check: ECS API revision 27 is HEALTHY; deployed Lambda code/revision is unchanged; mapping Disabled. See `aws-compatibility.json`.
- The original bundled API/Lambda reliability artifacts still match their existing manifest checksums. Their code and infrastructure plan were not changed.

## Artifact

`build/startup-only-lambda-release/startup-only-lambda.zip`

SHA256: `5858273fbb8a635ca5f84c33bfb02a3a215dae9db79031bfa79ace72cf7b94a5`

The complete machine-readable manifest is `release-manifest.json` in that directory. No local tests, source files, credentials or prototype artifacts are packaged; the deployable ZIP contains the existing runtime JAR set only.

## Future deployment boundary

No configuration or IAM changes are required. A separately approved code-only deployment would upload this ZIP under a new immutable key in the existing artifact bucket, verify the checksum, then update only the existing Lambda code using the recorded revision ID as a guard. Preserve the exact old ZIP for rollback. Keep the event-source mapping disabled and leave all ECS, IAM, timeout, memory, environment, networking and SQS settings unchanged.

Recheck the live code/configuration before that deployment. If the revision/checksum or configuration differs, stop for review. Do not deploy the bundled reliability ZIP instead.

This release does not solve the existing 60-second execution ceiling. No successful end-to-end processing duration or cold-start improvement is claimed. A paid PDF processing test and any decision to resume SQS require separate authorization and awareness of that existing limit.

## Deployment completed — 2026-10-24

The separately approved code-only deployment completed. Active Lambda revision ID: `96826267-fa7e-49e4-bb81-5444f7c4a4bd`. Deployed code SHA256 matches the artifact above. All recorded function configuration fields, including timeout, memory, environment, role, VPC and runtime configuration, compare unchanged. ECS revision 27 remains HEALTHY. The existing SQS mapping remains Disabled; source counts are 0/0/0, and the three DLQ messages remain present with no in-flight/delayed messages.

The exact ZIP was staged at a new content-addressed key in the existing artifact bucket, using a create-only conditional PUT and server-verified SHA256. The first upload exceeded a local 180-second transfer bound and was confirmed absent from S3. The identical guarded upload then succeeded with a longer local transfer allowance; no infrastructure timeout was changed. Lambda was updated only after successful checksum verification, using the baseline revision ID as a concurrency guard.

Full execution records are under `build/startup-only-lambda-release/deployment/`. No Lambda invocation, document processing, OpenAI request, database operation or SQS/IAM/ECS configuration update was performed. A proposed synchronous empty-record cold-start probe is documented in `deployment/proposed-cold-start-probe.md`; it has not been executed and requires separate approval.
