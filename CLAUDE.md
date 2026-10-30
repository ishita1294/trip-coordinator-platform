# Trip coordinator platform

Group and solo trip planning: itinerary with conflicts, AI travel-document extraction, and Explore
(free-time windows and place recommendations that fit before the next booking).

## Stack
- Backend: Java 25, Spring Boot 4.1, Gradle (Groovy `build.gradle`), single module under `src/`
- Database: PostgreSQL via JPA. `ddl-auto` defaults to `validate`; schema changes are hand-written
  SQL files in `db/manual/` and are applied manually. There is no Flyway yet.
- Frontend: React + Vite in `frontend/`, tests with Vitest
- AI: OpenAI Responses API via the official `openai-java` SDK, strict JSON-schema outputs
- AWS: Cognito (auth), S3 (documents), SQS + Lambda (document worker), ECS Fargate (API), RDS,
  Secrets Manager. Infrastructure is Terraform in `infra/terraform/` with LOCAL state files.

## Commands
- Backend tests: `./gradlew test`
- One test class: `./gradlew test --tests '*FeasibilityEngineTest'`
- Postgres tests only run when `AUTH_TEST_DATABASE_URL` points at the disposable local database
- Frontend: `cd frontend && npm test` and `npm run build`
- Lambda package: `./gradlew lambdaZip`

## Architecture rules
- Every trip endpoint goes through `CoreTripOperations` / `TripAccessService`. Non-members get 404.
  Child IDs (items, reservations, documents) must be checked to belong to the trip.
- AI proposes, the user confirms: extractions are saved as REVIEW_REQUIRED; reservations are only
  created by the confirm endpoint. AI ranking may only return IDs the backend supplied.
- Document processing uses the outbox, leased claims and attempt fencing. Any state write from a
  worker must check job ID and attempt ID.
- Never guess a time zone. Times are local values plus a zone resolved from reservation, flight,
  destination or the user; ambiguous or non-existent local times must be refused.
- Google Places content is never stored between requests (place IDs only) and must be attributed.
- Prefer returning response records over JPA entities from controllers.

## Working rules
- IMPORTANT: never make paid OpenAI or Google calls, run `terraform apply`, run AWS commands that
  change resources, or `git push` without asking first.
- Never read, print or commit `.env*` files or Terraform state.
- For changes touching more than one area (backend + frontend, or code + infra), propose a plan
  and wait for approval.
- Add or update tests in the same change; run them and show the output before saying it's done.
- Keep lines under 140 characters and methods short; do not write long one-line statements.
- Small commits with conventional messages (`feat:`, `fix:`, `perf:`, `test:`, `chore:`).

## Known open issues
- Members are added by raw user ID and the member list returns users' emails (privacy bug).
- `AttractionTourDocumentConfirmationService` and `TripService` have no tests.
