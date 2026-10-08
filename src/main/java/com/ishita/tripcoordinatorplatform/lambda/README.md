# Document processing Lambda runtime

Handler: `com.ishita.tripcoordinatorplatform.lambda.DocumentProcessingLambdaHandler::handleRequest`
Runtime: Java 25, matching the application's existing toolchain.
Build: `./gradlew lambdaZip` produces `build/distributions/document-processing-lambda.zip`.
The ZIP contains the plain application JAR and runtime dependency JARs in `lib/`,
which Lambda loads on its Java classpath. The normal Spring Boot JAR remains unchanged.

The SQS body must be exactly one object containing a positive integer documentId:
`{"documentId":19}`. Any failed record fails the whole batch; already handled
records can be redelivered safely through the existing claim/fencing services.
No partial batch response configuration is required by this adapter.

Claim/outcome mapping:
- CLAIMED: execute with the exact returned attemptId.
- ALREADY_ACTIVE: throw so the only retry is not discarded.
- TERMINAL (REVIEW_REQUIRED, PROCESSED, FAILED): acknowledge stale message.
- NOT_PROCESSABLE (including UPLOADED, missing document, or unexpected QUEUED
  observation after failed claims): throw rather than discard work.
- Execution SUCCESS or PERMANENT_FAILURE: acknowledge.
- Execution OWNERSHIP_LOST: throw; a later delivery re-evaluates state through claim.
- Retryable processing, parsing, claim, and infrastructure exceptions propagate.

One lazily initialized Spring context is reused across warm invocations. It starts
in non-web mode. Lambda-specific highest-priority properties disable the outbox
publisher/scheduling and select S3 storage, regardless of the API environment's
publisher/local-storage settings. No other scheduled jobs currently exist.
Supply the existing S3 bucket/region, datasource URL/username and secret ARNs
through runtime configuration; no credential values are embedded here.

Lambda uses the RDS endpoint configured by Terraform; local Docker PostgreSQL
remains a separate local-development option. The current SQS -> Lambda
event-source mapping is enabled.

Terraform defines the Lambda resource and the enabled SQS event-source
mapping in `infra/terraform/lambda.tf`.
Batch size must remain 1 with the current whole-batch failure handler; partial
batch responses are not configured.

Terraform supplies DOCUMENT_STORAGE_S3_BUCKET, DOCUMENT_STORAGE_S3_REGION,
SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, RDS_SECRET_ARN and OPENAI_SECRET_ARN.
The execution role can read bucket objects, consume the existing processing queue,
and write streams/events in the Terraform-managed Lambda log group. It cannot
create arbitrary log groups or access the DLQ directly.

Before creating Spring, the Lambda startup path loads both secrets using the SDK
default AWS credential and region chains. RDS's managed JSON must contain a
nonblank string `password`. Manually populate the new OpenAI secret after apply
with JSON containing a nonblank string `apiKey`, for example `{"apiKey":"<API key>"}`.
Terraform manages only the secret resource, never a secret version or value.
The execution role gets GetSecretValue only on these two secret ARNs.

Loaded values stay in memory as Spring properties (`spring.datasource.password`
and `app.ai.openai.api-key`). They are never logged or placed in Lambda environment
variables. Missing/unreadable/malformed secrets fail startup with sanitized errors.
The SDK client used to retrieve secrets is closed after loading; warm invocations
reuse the Spring context and its clients. Credential rotation takes effect when a
new execution environment starts; no refresh mechanism is added in this slice.

Local Spring Boot startup does not call this loader. Docker still supplies
SPRING_DATASOURCE_PASSWORD and OPENAI_API_KEY normally. The OpenAI components use
the secret-backed property only when present, otherwise their existing fromEnv path.
The NAT Gateway may separately be disabled for cost control through
`enable_nat_gateway`. Disabling NAT does not disable the SQS event-source mapping.
Without NAT or another outbound path, the worker cannot reach OpenAI or the
AWS public endpoints used for S3 and Secrets Manager; enabled consumption can
therefore lead to retries and eventual DLQ delivery. No Terraform apply is
performed by this application change.

Terraform uploads the ZIP to a dedicated private deployment-artifact bucket in
the same AWS region, using SSE-S3 encryption and full public-access blocking.
The object key is `document-processing/document-processing-lambda.zip`.
Lambda deployment references that object rather than directly uploading the ZIP;
the local source hash detects code changes. The runtime role has no access to the
artifact bucket. This bucket is separate from travel-document storage and may be
force-destroyed for dev cleanup; versioning and lifecycle rules are not configured.
The package must stay below Lambda's 250 MB total uncompressed ZIP limit.
