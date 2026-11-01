# V3.4 low-reasoning comparison candidate

## Evidence and limits

The approved 2026-10-22 extraction of `Tour_Confirmation_AWS_DEV.pdf` took 43,769.432333 ms. On 2026-10-23, one read-only GET of its existing OpenAI response recovered the missing metadata and JSON; no new inference was requested. The original measurement record was not overwritten.

- Model: `gpt-6-sol`; response reasoning effort: `medium`, mode: `standard`.
- Input: 5,778 tokens; cached input: 0.
- Output: 5,072 tokens, including 811 reasoning and 4,261 non-reasoning output tokens.
- One booking, five supplemental facts; JSON text: 17,642 characters.
- Source SHA256: `b2619f31b30a0b62ef68efa7c8c6ec9f4a2b7748da8ce0b5050a9bb3196215a7`.

Reasoning represents about 16% of output tokens. This does not prove that those tokens were unnecessary or explain how much wall-clock time they consumed. Most output is JSON carrying the contract's evidence, uncertainty and typed fields. Required nulls, empty lists and repeated evidence associations cannot simply be removed without reviewing contract semantics. No schema or prompt reduction was made.

Official model documentation confirms that medium is the default and low is supported: https://developers.openai.com/api/docs/models/gpt-6-sol. A lower effort is a supported performance candidate, not an established accuracy-preserving optimization. Low retains reasoning for contextual facts and lifecycle uncertainty; this slice does not add a second candidate.

## Exact change

`OpenAiAttractionTourDocumentExtractor` adds `reasoning: {effort: low}` only to V3.4 requests when `app.ai.openai.tour-v34-low-reasoning-candidate=true`. The environment switch is `TOUR_V34_LOW_REASONING_CANDIDATE`; its default is false. Opt-in rejects models other than exactly `gpt-6-sol` rather than guessing their supported settings.

All other request settings stay unchanged: model, source bytes/filename, instructions, strict JSON schema, generation settings, request budgets and SDK retry policy. Legacy, semantic V2 and correction-source assessment requests never use this candidate. V3.4 decoding, source provenance and validators remain unchanged. Logging now includes reasoning and non-reasoning output tokens when details are available; missing usage details do not prevent extraction.

The switch is not enabled in any runtime. No AWS configuration, queue, database, deployed artifact or frozen evaluation fixture changed.

## Prepared single comparison — NOT EXECUTED

Local files are in `build/v34-reasoning-comparison/`. They reuse the existing one-shot invocation pattern and production extractor, not Spring, classification, processing jobs or a second extraction implementation.

The offline dry run checks the PDF hash, model, candidate effort, exact original instructions and the original strict schema (ignoring only the order of JSON Schema `required` sets). The original schema builder uses Map.of iteration for some required-field lists, whose ordering can differ between JVMs; the comparison normalizes those sets only and does not modify the submitted schema. It constructs no real OpenAI client. The execution path permits only one Responses create call, has SDK retries zero, uses a complete HTTP request deadline of 90 seconds and creates a non-overwritable attempt marker before submission. Failure or timeout must not be rerun under this approval. No token-count request is made.

Only after explicit approval of ONE paid comparison, from a terminal with the existing secure `OPENAI_API_KEY` environment variable:

```sh
java -cp 'build/classes/java/main:build/v34-reasoning-comparison/classes:/private/tmp/v34-one-shot-latency-20261022/lib/*' com.ishita.tripcoordinatorplatform.ai.document.V34ReasoningComparison --execute-once
python3 build/v34-reasoning-comparison/compare_fields.py
```

No credential is embedded in these files. The temporary dependency directory is from the previously verified Lambda package; current compiled application classes take precedence. Recompile the prepared harness against current classes if source changes before the approved run.

The measurement saves request start/end/duration, request ID, status, usage breakdown, cached input, original generated JSON and the validated result. The comparison script reports every changed JSON path, booking counts and original indices; it does not treat matching the baseline as proof of source accuracy. Three offline checks exercise unchanged fields, changed time and changed lifecycle detection.

Before accepting the candidate, review the unchanged PDF against both saved outputs: booking count/association, reference/provider/name, dates/times/timezone, arrival boundary and conditions, meeting/ending locations, instructions, duration, lifecycle/uncertainty, replacement associations, policies, optional benefits, supplemental scope, evidence and missing fields. Review every changed status/value/condition and check for lost facts even if schema validation passes. Do not force baseline interpretations onto the source or promote unresolved lifecycle merely to match an expected result.

Compare total API duration with 43.769 seconds and reasoning/non-reasoning tokens with 811/4,261. Inspect cached-input differences; a single run cannot separate reasoning effort from provider load, caching, network variation or model nondeterminism. This is a candidate smoke comparison, not a population latency result or proof of general accuracy. No second baseline generation is authorized.

## Offline verification

49 focused tests passed: 5 candidate/request-isolation tests, 7 attraction/tour response tests, 13 semantic V2 extraction tests, 15 V3 contract tests and 9 V3.4 lifecycle invariant tests. Coverage includes exact request equality apart from reasoning, multi-booking preservation, source provenance, unsupported-model rejection, incomplete/invalid output rejection, correction isolation and optional usage metadata. Mocked responses verify implementation compatibility, not live-model accuracy.
