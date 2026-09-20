---
name: manage-roles-and-permissions
description: Add a role, or change what an existing role (ADMIN, STANDARD, READ_ONLY) is allowed to do. Use when asked to restrict or widen access to an endpoint or a UI action.
---

# Manage roles and permissions

Three roles today, in `backend/.../user/Role.java`: `ADMIN`, `STANDARD`, `READ_ONLY`. Authorization is
`@PreAuthorize` on controller methods — declared per endpoint, not inferred from URL patterns.

## The rule that matters

**The backend is the authority. The frontend is a convenience.** Hiding a button is not access control; anyone can
call the API directly. Every permission change starts in the controller, and the UI change is a follow-up so users
aren't shown actions that will 403.

## Checklist for changing an existing permission

1. Find the endpoint's `@PreAuthorize` in the relevant `*Controller`.
2. Change it. Prefer `hasRole('ADMIN')` over a fresh mechanism unless there's a real reason.
3. **Check for ownership logic**, which is separate from role. "Any STANDARD user may edit *their own* products" is a
   service-layer check, not an annotation — widening the role does nothing if the service still filters by owner.
4. Test the negative case. A test that the allowed role succeeds proves nothing about the denied one; assert the 403
   explicitly.
5. Audit: if the action is consequential, add an `AuditAction` and call `AuditService`. See the note in
   `docs/ARCHITECTURE.md` on why audit logging is explicit rather than AOP.
6. Frontend: hide or disable the control for roles that would now be refused.
7. Docs: `docs/API.md` states the required role per endpoint — keep it true.

## Checklist for adding a new role

Heavier than it looks. Consider whether a flag on `User` fits better before doing this.

1. `Role` enum constant.
2. Every `@PreAuthorize` in the codebase — decide explicitly whether the new role is included. The dangerous
   outcome is an endpoint nobody considered that happens to permit it because it uses `isAuthenticated()`.
3. `UserResponse` / user-management UI: the role must be assignable, and the dropdown must not let an admin lock
   themselves out.
4. Frontend route guards and any role-conditional rendering.
5. Tests: one per endpoint class asserting what the new role may and may not reach.
6. `docs/ARCHITECTURE.md` security section and `docs/API.md`.

## Two things that are not role checks

- **Bringing your own LLM key** is deliberately allowed for every role including `READ_ONLY` — it's a personal
  setting, not an administrative act.
- **`/actuator/**`** is unauthenticated by design (scrapers don't carry JWTs) and is restricted at the network
  layer. Don't "fix" it with a role. See `docs/DEPLOYMENT.md`.

## Verify

```sh
cd backend && mvn test
```

Then, against a running instance, log in as the role you restricted and call the endpoint with `curl` — not just
through the UI, since the UI is the part that can lie to you.
