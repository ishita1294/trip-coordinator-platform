# V3.4 compact JSON candidate

## Scope and activation

This is a direct-comparison candidate, disabled in normal processing. `OpenAiAttractionTourDocumentExtractor.extractCompactSemanticV3Candidate` appends only:

> Return compact JSON without whitespace outside strings. Do not omit or shorten any fields, values, evidence or uncertainty to achieve compactness.

The candidate explicitly uses `gpt-6-sol` and medium reasoning, matching the successful baseline. It rejects combining this experiment with the earlier low-reasoning candidate or another model. Schema, document representation, source bytes, semantic instructions, validation and production deadlines/retries are unchanged. There is no whitespace stripping, field abbreviation or post-response rewriting to conceal a noncompact model response.

Only the package-private direct comparison method enables the instruction. No new Spring property, controller, worker branch or extraction-contract identity was introduced. The ordinary `extractSemanticV3` interface still sends the original instruction and preserves its settings on subsequent calls. Existing jobs, historical extractions, immutable extraction hashes and retry selection are unaffected.

The direct comparison uses the existing V3.4 semantic contract and validators. Its local measurement record separately identifies `tour-semantic-3.4+compact-json-experiment-1` and the actual instruction SHA256; this label is not a new persisted contract. The runner has no database/finalization path. Production promotion and its instruction-version record require a separate reviewed decision; this experiment must not silently change worker behavior.

## Evidence

The unchanged synthetic PDF has SHA256 `b2619f31b30a0b62ef68efa7c8c6ec9f4a2b7748da8ce0b5050a9bb3196215a7`. The successful baseline took 43,769.432333 ms and used 5,778 input tokens, 811 reasoning tokens and 4,261 non-reasoning output tokens. Local o200k_base counting estimated 4,246 original JSON tokens versus 3,075 compact tokens (27.6% fewer). That encoding is a proxy, not a registered gpt-6-sol mapping or a latency prediction.

## One-request comparison — prepared, not executed

`build/v34-compact-comparison/` contains the same source PDF, original response, baseline model JSON, one-shot runner and local field-comparison script. No frozen evaluation artifacts were changed.

The offline dry run intercepts the production extractor's request, verifies the appended instruction and medium effort, compares the original strict schema and checks the source hash. Schema comparison ignores only ordering of JSON Schema required-field sets, which can vary with native Map.of iteration. The submitted schema is not modified. The dry run returns the saved response locally and confirms all original booking/supplemental values, evidence, uncertainty and coverage warning survive the same parser and validator. It performs zero network requests and creates no real OpenAI client.

After explicit approval for ONE paid comparison, use an existing terminal with `OPENAI_API_KEY` securely present:

```sh
java -cp 'build/classes/java/main:build/v34-compact-comparison/classes:/private/tmp/v34-one-shot-latency-20261022/lib/*' com.ishita.tripcoordinatorplatform.ai.document.V34CompactComparison --execute-once
python3 build/v34-compact-comparison/compare_fields.py
```

The runner allows one Responses create call, zero SDK retries and the same 90-second complete-request deadline as the original controlled measurement. An exclusive attempt marker prevents rerunning a failed or interrupted measurement. No classification, token-count API, SQS, Lambda, outbox or database call occurs. It records API start/end/duration, request ID, cached-input and reasoning/non-reasoning token counts, raw candidate JSON, validated extraction and actual instruction hash.

The field comparator reports whitespace outside strings separately from schema validity. It also reports every changed JSON path, original booking indices and booking counts. Review all 21 semantic facts, evidence, lifecycle uncertainty and supplemental applicability against the unchanged PDF; schema success or identical counts alone are not proof of accuracy. Whitespace inside strings must be preserved. Compare against the 43.769-second baseline without treating one sample or cache differences as proof of a general speedup.

## Focused offline verification

55 tests passed: four compact-candidate tests; the 49 existing reasoning/attraction/semantic/lifecycle checks; and two worker integration checks for normal source-bound V3.4 persistence and durable contract selection across retry/requeue/configuration changes. Actual request equality is checked after removing only the appended formatting instruction and the explicit medium setting; all other request fields, strict schema and source bytes match. Tests cover non-sticky activation, multi-booking preservation, source provenance, unresolved lifecycle, unchanged string content, invalid/incomplete output rejection and prevention of mixed reasoning experiments.

The saved successful response also passed the offline candidate-path validation with field equality. No paid comparison, AWS change, database migration, queue modification or deployment was performed. No speed or live-model accuracy improvement has been established.
