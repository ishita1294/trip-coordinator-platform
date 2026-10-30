# Docker development modes

Run commands from the repository root. Always select exactly one override.
The shared Compose file no longer chooses a datasource implicitly; using it
alone will not provide the datasource required by Spring Boot.

## LOCAL

Keep the existing ignored `.env` with `POSTGRES_DB=travel_db`, the existing
`POSTGRES_USER` and `POSTGRES_PASSWORD`. Do not replace credentials or volumes.

```sh
docker compose --env-file .env -f docker-compose.yml -f docker-compose.local.yml up -d --build app
```

This starts the existing PostgreSQL service if necessary. The API uses
`postgres:5432/travel_db`, local document storage, and a disabled publisher.
LOCAL currently does not support the frontend's S3-only presigned upload flow.
No replacement upload or local processing worker is implemented by these files.
Do not click Process expecting local worker execution; it can still create
QUEUED documents and PENDING outbox rows even with publishing disabled.

## AWS-DEV (secure runtime integration)

The new `.env.aws-dev.example` contains ONLY non-secret settings: RDS host and
username, existing RDS/OpenAI secret ARNs, S3 bucket, queue URL, and the absolute
restricted credential-cache directory. Do not put passwords, OpenAI API keys or
AWS credentials in new environment files. The currently populated `.env.aws-dev`
has deliberately NOT been changed during Stage 2A. Its sensitive entries must be
removed privately during the controlled migration; add the new non-secret entries
from the template at that time. The Compose override no longer consumes those old
sensitive entries, even while they remain in the old file.

Existing secret identifiers:

```text
AWS_DEV_RDS_SECRET_ARN=arn:aws:secretsmanager:us-east-1:393450686123:secret:rds!db-2a484790-2e07-4569-a1f9-43fc21d77532-IEEN7L
AWS_DEV_OPENAI_SECRET_ARN=arn:aws:secretsmanager:us-east-1:393450686123:secret:trip-coordinator-dev-document-processing-openai-mS5qk2
AWS_DEV_CREDENTIAL_CACHE_DIR=/Users/ishitababel/.cache/trip-coordinator/aws-dev
```

Refresh the host login only when needed, then assume the restricted API role:

```sh
aws login --profile trip-coordinator-dev
python3 scripts/refresh-aws-dev-credentials.py
```

Do not run these commands with shell tracing (`set -x`). Only the restricted cache
and its dedicated process-provider profile are mounted, read-only at `/run/aws-dev`.
The full host `.aws` directory and administrator login cache are never mounted.
No AWS credentials or application secrets are passed through Docker environment
variables. AWS SDK `credential_process` reads expiring restricted credentials;
refresh explicitly about every 45 minutes during development. Directory permissions
remain 0700 and file permissions 0600. Mount the directory so atomic file replacement
is visible. The current image runs as root, which can read the protected mount;
changing container UID later requires a deliberate compatibility review, not chmod
loosening. Docker daemon/container root can access the cache; it remains plaintext
short-lived authentication material outside the repository, not an encrypted vault.

Before approved recreation, validate privately (do not print full configuration):

```sh
docker compose --env-file .env --env-file .env.aws-dev -f docker-compose.yml -f docker-compose.aws-dev.yml config --quiet
```

After migration approval ONLY:

```sh
docker compose --env-file .env --env-file .env.aws-dev -f docker-compose.yml -f docker-compose.aws-dev.yml up -d --no-deps --build --force-recreate app
```

This retains RDS `tripcoordinator`, TLS, S3 in `us-east-1`, and the disabled publisher.
Trip 4 exists in RDS. No records are copied or moved. Existing PostgreSQL and its
volume remain untouched. Required ARNs/cache path cannot fall back silently.

With `RUNTIME_SECRETS_ENABLED=true`, the normal Spring entry point reads the existing
RDS password and OpenAI `apiKey` using the restricted SDK credential chain before
Spring initializes clients. Values are injected in memory as
`spring.datasource.password` and `app.ai.openai.api-key`, never as system properties,
CLI arguments or container environment variables. All OpenAI components share that
property. Retrieval/parsing failures terminate startup with sanitized messages;
there is no fallback to old AWS-DEV plaintext secrets. LOCAL disables this loader.
Lambda continues its existing cold-start secret-loading behavior with the same parser.

The SDK refreshes process credentials from the current cache when renewal is due.
If host renewal stops, AWS calls eventually fail when STS credentials expire. No
administrator fallback is available inside the container. Established database
connections and already loaded OpenAI credentials do not depend on STS continuously,
so AWS credential expiry is not a guarantee that the whole API stops. Secret rotation
requires a controlled application restart to reload passwords/keys; no automatic
connection-pool or OpenAI-key refresh is introduced here. Sanitized startup errors
do not imply all later AWS SDK operational errors are newly sanitized by this slice.

AWS-DEV loads the Google Maps key into `app.explore.google.api-key` from the dedicated
Secrets Manager secret only when `EXPLORE_GOOGLE_ENABLED=true`. Set the non-secret
`AWS_DEV_GOOGLE_MAPS_SECRET_ARN` after manually populating its JSON `apiKey` field.
Never put this key in environment files or container environment variables. LOCAL
may still supply `GOOGLE_MAPS_API_KEY` without enabling Secrets Manager. Lambda's
existing RDS/OpenAI secret-loading contract remains unchanged.

Google development safeguards distinguish identical requests from changed inputs. An
identical in-flight search returns409; a successfully completed identical search has
a 5-second refresh cooldown. Different origin, mode, preference, duration, freshness,
or commitment clarification is admitted immediately, subject to a rolling 6-starts/minute
limit per traveler, 2 active searches per traveler, and 8 active searches per process.
Failed requests release ownership without duplicate cooldown. Capacity-rejected requests
do not consume the rolling allowance. These limits are configurable through
`EXPLORE_DUPLICATE_WINDOW_SECONDS`, `EXPLORE_MAX_REQUESTS_PER_MINUTE`,
`EXPLORE_MAX_CONCURRENT_PER_TRAVELER`, and `EXPLORE_MAX_CONCURRENT_REQUESTS`.
A browser cancellation may leave the prior server search finishing; the second active
slot permits a changed request without pretending upstream work was cancelled.

Paid-call limits remain 47/request and 120/hour/process, shared by context lookups,
searches, and comparisons. The hour is a fixed one-hour window starting at process
initialization (then renewed on the first check after expiry); it is not Google's
quota or a per-user allowance. Failed network attempts count. Budget errors identify
request versus hourly exhaustion and provide the hourly reset time. Restarting the
process resets this development safeguard; do not restart to bypass cost limits.

Discovery retains up to 20 places and progressively evaluates candidates, starting
with 8 and extending when there are insufficient verified options. Independent
candidate checks run in up to 3 workers per admitted request; traffic/transit route
steps for an individual candidate remain sequential and time-specific. Configure
`app.explore.parallel-evaluations` (or `EXPLORE_PARALLEL_EVALUATIONS` when supplied to
the process) to use 1–3. This does not increase either paid-call allowance.

Initial loading reuses recently loaded application trip memberships (up to 60 seconds),
then returns opportunities and a provider-free commitment preview from one authorized
DB snapshot. Unknown destinations/timezones trigger the existing explicit lookup
after that response; slow Google resolution does not hold back the opportunity list.
Backend membership checks remain authoritative on every request.

Comparisons refresh the selected place facts once per request, share one authorized
schedule snapshot across scenarios, and calculate differences without AI reranking
or broad discovery. Identical Google operations are coalesced within that request;
no Google content is retained for later requests. Scenario times/modes remain part
of route request identity. Discovery still uses grounded AI ranking.

Explore More carries only deferred place IDs, evaluated IDs, coverage, and schedule
revision. It refreshes one coverage pool and skips earlier route assessments; a
missing deferred ID requires current Details. An explicit wider action goes directly
to the wider radius, and coverage is not replayed indefinitely. Provider changes,
exclusions, time/call budgets, and no additional qualifying places remain explicit.
Prior cards retain their original assessment and earliest expiry.

Logs contain aggregate stage milliseconds, call counts by provider operation,
request-scoped reuse, and hourly reset information, never coordinates/keys. Provider
operation milliseconds are summed network time and may overlap; stage wall times
must not be added to them to infer total latency. Nearby/current-hours/rating fields
remain unchanged (Places Enterprise); destination fields use Pro; traffic-aware
driving uses Routes Compute Routes Pro and basic walking/transit uses Essentials.
No automatic Google retries, persistent provider-content cache, infrastructure, or
new financial caps are introduced. Guards are process-local, reset on restart,
and use existing caller-supplied identity; they are not authentication, distributed
quotas, or hard billing caps. Browser aborts may leave already-started server work
finishing within the existing timeouts.

## Switching / safety

Validate the selected configuration with `config --quiet` before running either
command. To switch an already running app, append `--force-recreate` to the relevant
`up` command. Only `app` needs recreation. LOCAL may start PostgreSQL via its existing
dependency; AWS-DEV uses `--no-deps` and leaves PostgreSQL untouched.
Never run `down -v`, rename the Compose project, or delete the PostgreSQL named volume.
Keep the existing document directory. Changing modes does not migrate any data.
AWS-DEV retains Hibernate `ddl-auto=validate` and SQL initialization disabled; schema
changes require a separately reviewed migration.

Verify the live datasource and storage safely after an approved recreation:

```sh
docker exec trip-app printenv SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME APP_DOCUMENT_STORAGE_PROVIDER DOCUMENT_PROCESSING_PUBLISHER_ENABLED
```

Expected LOCAL: Docker `travel_db`, `local`, `false`.
Expected AWS-DEV: RDS `tripcoordinator?sslmode=require`, `s3`, `false`.
Do not print the full container environment. Read-only `GET /trips` can confirm
which data set the application sees.

For controlled AWS document-processing E2E only: first verify RDS developer ingress,
valid credentials, Lambda connectivity and outbound NAT, and review existing PENDING
outbox rows. Set `AWS_DEV_DOCUMENT_PROCESSING_PUBLISHER_ENABLED=true` explicitly
and provide the correct `AWS_DEV_DOCUMENT_PROCESSING_SQS_QUEUE_URL` before recreating
`app`. Enabling publishing can publish *existing* rows immediately. Mode switching
with the default disabled setting neither enqueues nor publishes document jobs.
Do not enable this while NAT is disabled. The Lambda event-source mapping remains
independently enabled; these files do not change AWS infrastructure or stop already
published work. Switching the API to LOCAL does not stop AWS services or their costs.

### Broad-interest relevance and bounded diversification

Broad discovery retains one popularity-ranked union search. If admitted results cover
fewer than three experience families, or one family dominates over 65 percent,
a single supplementary Nearby request asks for missing primary activity categories.
It runs only when at least seven Google attempts remain; no per-category fan-out
or retries are added. The merged pool is deduplicated and diversified before its
20-candidate limit. Explicit preferences keep their existing search requests.

Worship/heritage sites are one experience family, distinct from museums and other
cultural activities. No category quota or opening-hour guarantee is invented.
Generic landmark tags, traffic infrastructure and unsupported junction/public-space
listings do not establish visitor value just because they have high ratings.
Specifically typed plazas, parks, heritage sites and separately identified facilities
remain eligible. Candidate diagnostics explain rejected and admitted evidence.

Actual outbound/onward effort influences ranking; walking has a stronger soft effort
cost than driving. Review-volume confidence saturates instead of dominating activity
value. AI receives experience, practical score, requested mode and route/hour evidence
and can break close ties (0.2 score tolerance), but cannot override clear practical
or uncertainty advantages. Admission and deterministic schedule feasibility remain
separate. Generic-only landmarks may be conservatively omitted until stronger visitor
evidence is available.

A narrow broad pool can cost one extra Nearby request per coverage refresh; Nearby
uses the same rating/current-opening-hours field mask and associated billing SKU.
There is no added Place Details call solely for diversification. Route evaluation
remains progressive and bounded. Limits remain 47 per request and 120/hour/process.
An initial eight-candidate bounded driving workflow can use two discovery calls plus
up to 40 route calls (42 total), before any required commitment resolution. Overall
attempt limits still apply and incomplete coverage is reported. Compared with one
19-call search, one extra discovery attempt is about 5 percent more attempts, not
a claim of equal per-operation prices or a hard spending cap.
