# AWS-DEV document-worker capacity

Warm-worker startup is explicitly opt-in. Normal on-demand environments retain lazy Spring initialization. `DOCUMENT_WORKER_PROVISIONED_BOOTSTRAP=true` takes effect only when AWS supplies `AWS_LAMBDA_INITIALIZATION_TYPE=provisioned-concurrency`; never set AWS's variable manually.

Use the established project credential profile; no new credential mechanism is introduced.

```bash
# Opt in after AWS-DEV dependencies are available:
python3 scripts/ecs_aws_dev.py warm-worker-enable

# Stop paying for warm capacity, without deleting the function/version/alias/mapping:
python3 scripts/ecs_aws_dev.py warm-worker-disable

# Existing ECS shutdown: document drain check -> capacity removal verified -> API stop:
python3 scripts/ecs_aws_dev.py stop
```

Both capacity commands are idempotent. Enable verifies the alias version/configuration and the existing mapping, requests one environment only if needed, and waits for READY. Disable deletes only the provisioned-concurrency configuration and confirms its absence before returning. The mapping remains on the alias; with capacity absent its invocations use lazy on-demand initialization. Existing `start` does not allocate warm capacity automatically. Enable only after normal start/dependency readiness. No scheduler is added.

Before shutdown, close application writes and drain normal work. The existing ECS stop command now fails closed if visible/in-flight/delayed source messages or pending/active/recoverable database processing work exists, the established ECS/SSM read-only inspection is unavailable, or capacity removal cannot be verified. It does not delete/replay jobs or change mapping availability. It stops ECS only, not RDS/NAT; any separately approved database/network shutdown must happen afterward. Do not stop RDS/networking while provisioned allocation exists, because AWS may recycle and reinitialize environments.

Provisioning initializes secrets, Spring, database validation and clients in advance. It does not invoke document handling or inference and does not make extraction itself faster. Database connection headroom and secret/network access must remain available. Capacity1 does not guarantee all concurrent deliveries avoid spillover.

At the reviewed US-East x86 1GB rate, capacity1 is approximately $0.015/hour (~$10.80/30days continuously), plus requests, initialization and execution charges. Disable when unused. Verify pricing before materially expanding use.

## Release / rollback

Frozen release: `build/provisioned-worker-release-20261029/release-manifest.json`. The ZIP changes only the outer handler class; its generated lambda body is inside that class. Existing nested classes, dependencies, resources, full schema, model/effort/tier and validators are preserved. Timeout120s, lease180s, queue visibility720s, max deliveries5, batch1/window0 remain unchanged. Inactive larger budgets, extraction experiments and source-action prompt candidate are excluded.

Preflight preserves exact rollback.zip, Lambda configuration, original mapping target/state, ECS configuration and read-only data fingerprints. Deploy code and opt-in environment, publish version, create `document-worker` alias, allocate1, wait READY and verify version-specific Spring-ready logs. Only then retarget the existing mapping UUID; never create a second mapping. Preserve its prior Enabled/Disabled state.

Rollback requires separately controlled idle/drain checks: pause the existing mapping, confirm no processing ownership/work, remove provisioned allocation and verify absence, restore its saved original function target, restore exact rollback code/configuration with revision guards, verify health, then restore prior consumption state. Retain the new version/alias as evidence; no schema/data rollback is required. Do not redirect in-flight processing or automatically roll back after an unexpected failure.

No manual invocation, upload or provider request is part of bootstrap verification. Startup timing does not prove normal-processing latency or document correctness.

## Current release status (2026-10-29)

BLOCKED before AWS mutation. Frozen ZIP SHA256 `d0cb6b9f88ba7f7a773b4084ed1b818ad4875103ae9dc413561bf3ec9de03ee3` and rollback ZIP verified; no rebuild. Refreshed ECS43 and exact Lambda baseline remain healthy; existing mapping Enabled, source counts0/0/0, DLQ4/0/0. Regional Lambda concurrent execution limit and unreserved capacity are both10. AWS requires leaving100 unreserved units, so allocating1 needs at least101 unreserved capacity. No quota request, upload, deployment, alias/version/capacity or mapping change occurred. Database refresh was not reached because the quota gate failed; its prior idle evidence must be refreshed after this blocker is resolved. Resume this frozen release only after a separately approved quota increase is effective.
