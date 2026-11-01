# V3.4 extraction streaming timing diagnostic

Prepared locally on 2026-10-23. No live request is authorized or executed by this preparation.

## Scope

`build/v34-streaming-diagnostic/V34StreamingDiagnostic.java` uses the ordinary `OpenAiAttractionTourDocumentExtractor.extractSemanticV3` method to build the request and decode/validate the completed response. A guarded client replaces its single blocking create operation with one SDK `createStreaming` call. The original semantic instructions, strict schema, PDF representation and source bytes are checked against the existing benchmark before any real client is constructed. JSON Schema required-field arrays are compared as sets; the submitted schema is never rewritten.

The request uses `gpt-6-sol`, explicit medium reasoning, original PDF `Tour_Confirmation_AWS_DEV.pdf` (SHA256 `b2619f31b30a0b62ef68efa7c8c6ec9f4a2b7748da8ce0b5050a9bb3196215a7`), zero SDK/application retries, and a 90-second whole-operation deadline. SDK connect/write/read/request deadlines are 5/10/90/90 seconds. An additional timed future covers streaming-body consumption, closes the response on deadline and rejects late events. No compact-format instruction or low-reasoning candidate is enabled. Production classes, runtime configuration, processing jobs and persisted contracts are unchanged.

An exclusive `request-started-once` marker prevents another execution, including after interruption or failure. Never remove it to retry. Only `OPENAI_API_KEY` already present in the invoking process is used; the runner does not retrieve credentials, use AWS, start Spring, classify the document, count tokens through an API, retrieve responses through an API, or contact a database/queue. No key, raw SSE event or reasoning text is logged.

## Measurements

All intervals use `System.nanoTime()`. Wall-clock UTC request initiation is recorded separately for correlation.

| Recorded event / interval | Meaning |
| --- | --- |
| Request initiation | Immediately before entering the bounded streaming operation; includes client thread scheduling and HTTP setup |
| Headers received | SDK has returned the HTTP response headers, before consuming SSE events |
| First response event | First SDK-parsed SSE event observed locally |
| First / final visible JSON delta | First / final nonempty `response.output_text.delta` observed locally; formatting whitespace remains part of the output |
| Response completion | Receipt of `response.completed`, independently of later V3.4 validation |
| Before visible JSON | Request initiation to first nonempty JSON delta |
| Visible JSON generation span | First to final nonempty JSON delta; a single-delta response yields zero, not evidence of instantaneous server generation |
| Completion after final delta | Final delta to completion event |
| Total to completion | Initiation to completion event |
| Client operation duration | Through stream completion/closure, or failure observation |

The report includes input/output/cached-input/reasoning/non-reasoning tokens when available, response ID, server created/completed timestamps, HTTP status, and an allowlist of headers (`x-request-id`, `openai-processing-ms`, `server-timing`, `date`, `openai-version`). Missing metadata stays missing. Timing headers are preserved as provider-reported values, not treated as a breakdown of PDF parsing or reasoning.

These are client-observed event arrival intervals, affected by SDK parsing, transport buffering and scheduling. Before visible JSON can include network latency, queueing, PDF text/image preprocessing, internal reasoning and other provider work. The API does not give a reliable separate duration for each. Visible delta arrival span is not pure server decoding time. Streaming may itself affect delivery characteristics, so compare the diagnostic's own total with historical non-streamed measurements cautiously.

Official references: [Streaming responses](https://developers.openai.com/api/docs/guides/streaming-responses), [Structured Outputs streaming](https://developers.openai.com/api/docs/guides/structured-outputs#streaming).

## Completion and validation

Deltas are accumulated per message/content part. Text-done events must exactly match their deltas; the completed response text must exactly match the reconstructed JSON. Missing deltas, unfinished text, response errors, refusals, failed/incomplete responses, interrupted streams and missing completion events fail the diagnostic. No partial JSON is submitted to the decoder or marked valid.

Only after successful reconstruction does the existing extractor perform its unchanged completion checks, V3.4 decoding, source-hash provenance construction and semantic validation. A completed but semantically invalid response is retained as raw model JSON and marked as failed validation. A partial/inconsistent stream is not saved as a completed model output.

Live output files are `result.json`, `model-output.json` (completed/reconstructed only), and `validated-extraction.json` (validation success only), all under the local build directory. No document contents are printed. Exceptions are reported by class rather than untrusted provider messages.

## Offline verification

20 deterministic checks passed using the saved baseline response, in-memory streaming events and a controllable monotonic clock. They cover original request/schema/PDF preservation, explicit medium reasoning and candidate exclusion, field/evidence preservation through the actual extractor, all timing calculations, cached/reasoning token accounting, empty/single deltas, truncation, inconsistent/missing deltas, missing text-done/completion, provider errors, failed/incomplete responses, transport timeout, bounded cancellation, rejection of late events, unchanged decoder/validator rejection, streaming SDK method/timeout/header wiring and one-call behavior after HTTP failure. No real provider client is constructed by these checks.

From the repository root, compile the local runner and tests using the existing current application classes and previously verified SDK dependencies:

```sh
javac -cp 'build/classes/java/main:/private/tmp/v34-one-shot-latency-20261022/lib/*' -d build/v34-streaming-diagnostic/classes build/v34-streaming-diagnostic/V34StreamingDiagnostic.java
javac -cp 'build/classes/java/main:build/v34-streaming-diagnostic/classes:/private/tmp/v34-one-shot-latency-20261022/lib/*' -d build/v34-streaming-diagnostic/classes build/v34-streaming-diagnostic/V34StreamingDiagnosticTest.java
java -cp 'build/classes/java/main:build/v34-streaming-diagnostic/classes:/private/tmp/v34-one-shot-latency-20261022/lib/*' com.ishita.tripcoordinatorplatform.ai.document.V34StreamingDiagnosticTest
```

## Future single live run — requires new explicit approval

Use an existing secure terminal with `OPENAI_API_KEY` already available. Do not print it. The following command makes ONE paid request and must not be run during preparation:

```sh
java -cp 'build/classes/java/main:build/v34-streaming-diagnostic/classes:/private/tmp/v34-one-shot-latency-20261022/lib/*' com.ishita.tripcoordinatorplatform.ai.document.V34StreamingDiagnostic --execute-once
```

The temporary dependency directory must still exist. If application classes, prompt/schema or runner change, recompile and repeat offline verification before the separately approved live run. A missing key or mismatched benchmark stops before submission. Prior model-comparison authorizations do not apply to this diagnostic.
