# V3.4 lean model-output feasibility

Offline prototype completed 2026-10-24, continuing the compiled prototype left by the interrupted session. No matching prototype process remained running. Existing artifacts were preserved. No provider client, paid request, AWS operation, database operation or production extraction change was used.

## Decision

**NO-GO for promoting this particular contract to production.** Lossless reconstruction is feasible and output savings are real in local token estimates. However, its explicit strict fact variants expand the schema from about 4,468 to 15,411 input tokens. Model accuracy at emitting pooled references, and actual latency with that larger schema, remain unverified. The proof is useful; a production performance improvement is not established. No model, prompt, schema, extraction contract or processing route was changed in the application.

## One proposed wire contract

The full proposed JSON Schema is `build/v34-lean-contract/proposed-wire-schema.json`. It is generated from the current V3.4 shapes for this prototype only. The root has:

- `wireVersion: 1` — local prototype identity, never a production persisted contract.
- `texts` — exact source quotation/expression strings, with exact-string deduplication only.
- `evidence` — rows containing `bookingIndex`, `bookingAssociation`, `quoteId` and `reportedPage`. A quote ID points into `texts`. Distinct associations, pages and booking identities remain distinct evidence rows even when their quotation text is shared.
- `bookings` — original source-order booking indices and existing booking fields, with tagged fact representations.
- `supplementalFacts` — the existing explicit applicability, role, modality, conditionality, trigger, qualifications and information, with tagged facts and typed details.
- `supplementalCoverageWarning` — unchanged, including a non-null warning when supplied.

Each semantic fact uses a `kind` tag combining its original status and interpretation basis, such as `CLEAR_EXPLICIT`, `CLEAR_CONTEXT_INTERPRETED`, `CLEAR_CALCULATED` or `AMBIGUOUS_NOT_ESTABLISHED`. These tags encode the AI's decisions; Java does not decide that a fact is clear or explicit. Fields required by the selected variant are explicit in the strict schema; optional-field omission is not used to evade strict output requirements.

For example, an established name fact can be represented as:

```json
{"kind":"CLEAR_EXPLICIT","uncertainty":null,"value":"Paris Historic Quarter Guided Walk","evidence":[0],"sourceExpressions":[0]}
```

A calculated fact retains the AI-selected operation, ordered operand references, wall-clock/elapsed basis, factual dependencies, evidence, expressions and uncertainty. Its numeric or temporal result is omitted. Java computes that result, then the unchanged validator independently checks the reconstructed V3.4 representation. Unknown facts keep their alternatives, evidence, uncertainty and interpretation; no unresolved result is calculated or selected.

Typed supplemental details use `{type,value}` plus `unit` for quantities, instead of six mutually exclusive nullable value slots. The type remains an AI decision. Other domain values, temporal boundaries/inclusivity, lifecycle states, replacement references, booking indices, source associations and supplemental applicability remain explicit. No keyword-based inference, evidence matching or automatic lifecycle selection is introduced.

## What Java constructs

- The full V3 schema envelope and trusted provenance supplied by the caller.
- Original strings and evidence records by exact table lookup, preserving list order and duplicate evidence occurrences.
- Status/basis from the explicit tag, never from the value or document wording.
- Required structural nulls and empty lists fixed by the selected valid fact form: for example, CLEAR alternatives are empty; unresolved values are null; EXPLICIT/NOT_ESTABLISHED dependencies are empty; non-calculated calculation slots are null. Uncertainty is retained explicitly, even for CLEAR/MISSING facts.
- Inactive typed-detail slots as null, using the explicit detail type.
- `MINUTES` as the only supported calculation unit; operation, operands and temporal basis remain explicit.
- Calculation results using local calendar or uniquely resolved elapsed-time arithmetic. DST gaps/overlaps, cross-booking operands, cycles and unknown operations are rejected. The existing V3.4 validator still checks operation/target compatibility, dependencies and all final semantics.

The prototype duplicates a small amount of arithmetic in a standalone helper to demonstrate expansion. Production integration would need one shared, tested arithmetic implementation rather than maintaining two independent calculators. Source hashing and provenance are already backend responsibilities; they are not new output-token savings.

## Verification

`LeanV34FeasibilityTest.java`: **23 focused checks passed**, including **13 exact canonical JSON and domain-object round trips**. The round trips preserve all fields, original evidence strings, ordering, source expressions, uncertainty, relationships, applicability, warning and supplied provenance, then use the unchanged V3.4 decoder/validator.

Coverage:

- Four saved successful outputs: original benchmark, compact response, new Sol response and streamed response.
- Existing multi-booking V3.4 fixture.
- Cancelled predecessor/active replacement; superseded predecessor/provisional replacement.
- Missing/conflicting lifecycle and coverage warning.
- Partial date and ambiguous location.
- Conditional temporal window with independent strict/inclusive bounds.
- Shared/uncertain policy applicability, qualifications and all five supplemental detail types.
- Unique-offset elapsed calculations.
- Historical 3.1 record through its existing reader, without modifying the saved record.
- Negative checks for unknown wire version, dangling references, cross-booking evidence/arithmetic, missing uncertainty, unknown fields, invalid saved Luna semantics, DST ambiguity and calculation cycles.

The complex cases reuse existing compiled fixture builders and extend only their in-memory copies using existing domain rules. They are synthetic representation checks, not newly generated AI results or new source-accuracy evidence. Stored historical data and frozen evaluation artifacts were not modified. These checks do not retest live correction or confirmation workflows; compatibility is demonstrated by equality of the expanded domain representation consumed by those services.

All **13 encoded cases also passed the proposed JSON Schema** under the local Draft 2020-12 validator. Actual OpenAI acceptance of this exact schema has not been tested. No provider request was made.

## Token estimates

The same cached `tiktoken 0.14.0` / `o200k_base` encoding used by the formatting investigation was used, with socket connections disabled. This is a proxy tokenizer, not a registered exact `gpt-6-sol` tokenizer. No token-count API was called.

| Response / fixture | Original generated JSON | Compact original | Lean | Structural reduction vs compact |
| --- | ---: | ---: | ---: | ---: |
| Original Paris benchmark | 4,246 | 3,075 | 2,497 | 18.8% |
| Latest streamed Paris response | 4,489 | 3,269 | 2,556 | 21.8% |
| Existing multi-booking fixture | 2,959 | 2,959 | 2,248 | 24.0% |
| Cancellation/replacement fixture | 3,041 | 3,041 | 2,319 | 23.7% |
| Shared/uncertain policy and typed details | 3,897 | 3,897 | 2,901 | 25.6% |

The original benchmark is measured from the actual text in `baseline-response.json`. Its separate `baseline-model-output.json` copy was reformatted earlier; parsed equality holds, but using that copy's whitespace would inflate the estimate. Existing files were not rewritten.

Across all 13 cases, structural output savings are approximately **15.2–25.6%**. The original benchmark's 41.2% reduction and streamed response's 43.1% reduction versus generated JSON include ordinary formatting savings; those must not be attributed solely to the contract. Compact-JSON experiments already showed that fewer tokens do not guarantee lower latency.

Schema estimates: **4,468 original → 15,411 proposed** (about **10,943 additional input tokens**). These counts compare minified schemas only; they are not full provider input-token measurements and do not include instructions or PDF vision tokens.

Full measurements are in `build/v34-lean-contract/token-comparison.json`.

## Compatibility and remaining risks

1. The model has not produced this representation. Fewer output tokens may be offset by harder reference bookkeeping, invalid indices, larger-schema processing or additional reasoning.
2. Table lookup proves structural preservation, not that quoted text really occurs in the PDF. Existing AI-reported evidence limitations remain; the prototype does not elevate evidence to independent verification.
3. Java can reconstruct a supported calculation result but cannot compare it with a model-generated result that is no longer supplied. Existing final validation remains, but this removes that redundant model/backend agreement check. Operation choice, operands and source meaning still need strong source-level evaluation.
4. Unknown fields, statuses, scopes and unsupported arithmetic must fail closed; no fallback or silent source repair is acceptable.
5. Any production successor needs an explicit new wire/prompt contract identity and truthful provenance, plus durable worker contract selection across retries. It must not masquerade as the exact historical V3.4 request. Canonical stored domain compatibility does not eliminate that versioning requirement.
6. Production promotion would require separate review and provider evaluation. This task authorizes neither. The current prototype/schema should not be wired into processing.

## Files and reproduction

All executable work is local under `build/v34-lean-contract/`:

- `LeanV34Prototype.java` — existing checkpoint encoder/expander, preserved.
- `LeanV34FeasibilityTest.java` — fixture reuse, round trips and negative checks.
- `check_schema_and_tokens.py` — proposed schema generation, local schema validation, cached tokenizer comparison; no network.
- `test-classpath.txt`, `classes/`, `cases/`, `verification.json`, `proposed-wire-schema.json`, `token-comparison.json` — local verification artifacts.

The classpath references existing compiled production/test classes, fixture resources and cached dependencies. Compile/run with:

```sh
javac -cp "$(cat build/v34-lean-contract/test-classpath.txt)" -d build/v34-lean-contract/classes build/v34-lean-contract/LeanV34Prototype.java build/v34-lean-contract/LeanV34FeasibilityTest.java
java -cp "$(cat build/v34-lean-contract/test-classpath.txt)" com.ishita.tripcoordinatorplatform.ai.document.LeanV34FeasibilityTest
python3 build/v34-lean-contract/check_schema_and_tokens.py
```

Only this engineering record was added outside the local build directory. No staging, commits, production configuration or application changes were performed.
