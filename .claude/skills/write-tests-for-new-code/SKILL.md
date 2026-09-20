---
name: write-tests-for-new-code
description: Write tests that match this repo's conventions — what to mock, what to run against a real MongoDB, and what a test here is expected to prove. Use when adding or changing backend or frontend code.
---

# Write tests for new code

758 backend tests (JUnit 5 + AssertJ + Mockito, some with Testcontainers) and 37 frontend tests (Vitest + React
Testing Library). Conventions below are observed, not aspirational — read a neighbouring test file before writing.

## What this repo tests, and how

| Kind | Mock or real | Where |
|---|---|---|
| Service logic | Mockito the repositories | `backend/src/test/java/.../<pkg>/XxxServiceTest.java` |
| Controller: status, JSON shape, authorization | `@WebMvcTest` + MockMvc, service mocked | `XxxControllerTest.java` |
| Mongo queries — dynamic filters, count-before-paginate | **Real MongoDB via Testcontainers** | `XxxRepositoryMongoTest.java` |
| Config correctness | Assert against the real `application.properties` | `config/ApplicationPropertiesBindingTest.java` |
| React components | Vitest + RTL, API layer mocked | `frontend/src/**/*.test.tsx` |

**Query tests use a real database on purpose.** A mocked repository returning a canned list proves the mock works,
not the query. They fail rather than skip when Docker is missing — a silently skipped test reporting as a pass is
worse than a red build.

## Rules worth stating

1. **Assert the negative case.** A test that the permitted role succeeds says nothing about the denied one. Assert
   the 403, the 401, the validation 400.
2. **No secret may appear in an assertion of an audit log, a response, or a log line.** Where a test asserts an
   audit entry, assert what the `details` map *doesn't* contain too — that's the invariant, and it's easy to break
   accidentally.
3. **Never call a real LLM or crawl a real site.** Stub `LlmProvider`; serve local fixtures to the crawler.
4. **Guard your guards.** If a test iterates over something discovered at runtime (properties, files, classpath
   entries), add a test asserting the collection is non-empty — otherwise the day it discovers nothing, every
   assertion passes vacuously. `ApplicationPropertiesBindingTest.theMetadataScanFoundSomethingToCheckAgainst` is the
   pattern.
5. **Name the consequence, not the mechanism.** `theMongoConnectionUriUsesTheNameBootFourBinds` beats
   `testMongoUri`. Where a test encodes a bug that was actually hit, say so in a comment — the next person needs to
   know why the assertion is oddly specific.
6. **A regression test must be shown to fail.** Revert the fix, watch it go red, restore. A regression test that was
   never seen failing may be asserting nothing.
7. **Frontend: assert what the user sees.** Query by role and text, not by class name or test id where a real
   accessible query exists.

## Checklist

1. Put the test in the same package as the code — `RunMetricsDistributionPropertiesTest` lives in `run` specifically
   so it can use `RunMetrics`' package-private constants instead of string literals.
2. Cover the happy path, the validation failure, the authorization failure, and the "downstream thing broke" path.
   For a run, that last one means the outcome is `partial`, not a thrown exception.
3. Reference constants, not copies of their values. A test that hardcodes `"saas.run.duration"` won't notice a
   rename; one that uses `RunMetrics.RUN_TIMER` will.
4. Run the full suite, not just your new file — the config tests are global and your new property may break one.

## Verify

```sh
cd backend && mvn test       # then target/site/jacoco/index.html
cd frontend && npm test -- --run
```

There's no coverage *gate* yet — JaCoCo reports, `jacoco:check` isn't bound. That's deliberate; turning it on is the
`enable-coverage-gate` skill. Until then the report is for you to read, not a number to satisfy.
