---
name: add-a-new-endpoint
description: Add a REST endpoint to the backend and wire it to the frontend. Use when asked for a new API route, a new admin action, or a new piece of data the UI needs.
---

# Add a new endpoint

The layering is consistent across every package under `backend/src/main/java/com/saasinvestigator/`: Controller
(HTTP + authorization) → Service (logic) → Repository (Mongo). Match the neighbours; a new endpoint should be
unremarkable.

## Checklist

1. **Request/response records** — `XxxRequest` with Bean Validation annotations, `XxxResponse` as a record with a
   static factory from the entity. Never return the entity itself: that's how a password hash or an encrypted
   credential ends up in JSON.
2. **Controller method** on the existing controller for that resource, if there is one.
   - `@PreAuthorize` — always explicit, never inherited or implied. See `manage-roles-and-permissions`.
   - `@CurrentUser AuthenticatedUser` for the caller, rather than digging in the `SecurityContext`.
   - Correct status: `201` + body on create, `204` no body on delete, `200` otherwise.
3. **Service method** — this is where the logic goes. Throw `NotFoundException` / `BadRequestException` /
   `ConflictException`; `GlobalExceptionHandler` turns them into the app's JSON error shape. Don't build
   `ResponseEntity` error bodies by hand and don't let an exception reach the client as a 500.
4. **Ownership checks** belong here too, not in the controller. Role says *what kind of thing* you may do; ownership
   says *which rows*.
5. **Pagination** — anything list-shaped that can grow uses `PageResponse` / `Paging` from `common/`. An unbounded
   list endpoint is a future outage.
6. **Audit** — consequential state changes get an `AuditAction` and an `AuditService` call. Details must never
   contain a secret, a password, or a key tail.
7. **Index** — if you added a query, check `config/MongoIndexInitializer`. Indexes are declared there explicitly, not
   derived from annotations (`spring.data.mongodb.auto-index-creation=false`).
8. **OpenAPI** — `@Operation` / `@ApiResponse` so `/swagger-ui.html` stays accurate.
9. **Frontend** — a function in the matching `frontend/src/api/*.ts`, then a TanStack Query hook. **Invalidate the
   queries a mutation affects**, or the UI shows stale data until a reload and it will be blamed on the backend.
10. **Tests** — controller test for status + authorization (including the 403), service test for the logic. See
    `write-tests-for-new-code`.
11. **Docs** — add it to `docs/API.md` with its required role. See `update-docs`.

## Verify

```sh
cd backend && mvn test
cd frontend && npm test -- --run
```

Then `curl` it three ways: with no token (expect 401 in the app's JSON error shape), with a token of a role that
shouldn't have it (expect 403), and with the right role (expect the real thing). The first two are the ones people
skip and the ones that matter.
