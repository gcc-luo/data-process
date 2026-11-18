# Granular module and operation permissions

## Goal

Replace the overlapping menu grants and broad module grants with a single, auditable permission model. Menus remain navigation containers; concrete operation permissions are the authorization source. Roles can receive selected operations such as `system.department.create` or `system.department.delete`, and every protected API checks the corresponding operation on the server.

## Decisions and acceptance criteria

- Permission codes are stable backend identifiers, grouped by module and action, and seeded from the application’s supported permission registry.
- The permission catalog is visible under System Management. Catalog entries are code-owned; adding an unknown code in the database or request must never grant access.
- The role editor presents a module/action tree. Selecting a module selects all its actions; partial selections are shown as partial. There is one grant source (`role_permissions`), not independent menu and function grants.
- Menus have no permission-code field. A visible menu is included in navigation when the user has at least one permission in its module; parent directories are included automatically. Permission checks remain server-side and default-deny.
- Existing broad grants are migrated to explicit operations while preserving the default administrator’s access. Data-scope policies stay separate from operation grants.
- System Management keeps its current children and gains Permission Management. Existing user changes and the earlier menu/icon work are preserved.
- Existing functional flows continue to compile, requests lacking an operation grant are denied, and permitted operations succeed.

## Implementation sequence

### 1. Registry and schema

- Add the application permission registry with module metadata, action metadata, and API-to-permission mappings.
- Add Flyway migration V25 in both migration locations, register the catalog, add System Management > Permission Management, and convert existing broad role grants into concrete grants. Keep the migration safe for existing installations and preserve administrator access.
- Remove menu permission-code storage from new writes and stop using `role_menus` as an authorization source. Keep legacy columns/tables untouched for upgrade compatibility unless a later migration can safely remove them.
- Acceptance: a fresh/current MySQL installation has the catalog and permission menu; legacy roles retain equivalent allowed operations; unknown codes cannot be assigned.

### 2. Backend authorization

- Replace URL groups in `SecurityConfiguration` with HTTP-method/path permission mappings and narrowly scoped controller checks where one URL supports distinct read/write operations.
- Split organization reads and writes by resource/action so a role with Department Create cannot implicitly read users, roles, menus, and audit data.
- Return navigation filtered by module permissions and role-session permission codes. Enforce default deny for unmatched protected endpoints.
- Acceptance: every mutating/read API used by the frontend has a defined operation permission; each request is checked server-side; administrator remains usable.

### 3. Permission catalog and role editor UI

- Add the Permission Management page to the system menu and display searchable module/action metadata without offering unsupported arbitrary permission creation.
- Replace the separate function and menu grants with one compact hierarchical role authorization dialog. Display checked/partial/unchecked parent state and save concrete permission codes.
- Remove permission-code editing from the menu form. Gate action buttons by operation grants where practical; backend enforcement remains authoritative.
- Acceptance: admins can configure per-action grants; a role with only create can create but cannot list/read/update/delete; navigation only includes modules the user can enter.

### 4. Upgrade and verification

- Review migration idempotency/compatibility and all endpoint mappings, then build backend and frontend and run `git diff --check` (no test suite unless separately requested).
- Acceptance: both builds succeed, working tree contains no accidental changes, and the final report lists implemented areas and any external-only validation gaps.

## Review checkpoints

- After registry/migration: check key naming, safe legacy-grant conversion, and no implicit grant from client-supplied strings.
- After backend: check every controller route against the registry and ensure response payloads do not bypass per-resource read permissions.
- After frontend: check selection semantics, partial module state, and action visibility against saved operation codes.
- Before completion: inspect the full diff and fresh build output.

## Execution status

- [x] Register stable module/action permissions and add the V25 Flyway migration in both configured resource trees.
- [x] Convert legacy broad permissions, clear legacy menu grants, and keep the default administrator fully authorized.
- [x] Enforce exact method/path permissions server-side; unknown protected APIs and unregistered permission codes fail closed.
- [x] Filter organization snapshots and navigation by grants; protect permission delegation and role assignment from privilege escalation.
- [x] Replace menu/function dual grants with the module/action authorization tree and permission catalog page; gate actions across business pages.
- [x] Backend compile, frontend production build, route-map coverage review, and `git diff --check` pass.
- [x] Avoid reserving a fixed menu ID for Permission Management so existing custom menus cannot block the upgrade; keep both Flyway resource copies synchronized.
- [x] Apply V24/V25 to local MySQL and verify application startup. Verified on 2026-11-18 against the local MySQL instance on 127.0.0.1:3307 (application account `governance`); backend starts on 8080, admin login and navigation verified end to end.

## Follow-up: navigation scope and menu management (2026-11-18)

Owner requirement: the sidebar keeps only System Management and its second-level pages; platform modules are intentionally not navigation entries (this is what V24 encodes). Runtime verification and cleanup completed within that scope:

- `navigation()` stays database-driven and returns only the System Management menus permitted by the user's module grants. An interim change that re-added code-owned platform menus was reverted because it contradicted the requirement.
- Menu management only maintains System Management pages: `validateMenuPlacement` rejects registering platform components, and the menu form no longer offers them, so the system-only sidebar cannot be broken from the UI/API.
- The H2 copy of V25 was rewritten into H2-compatible SQL (constraint syntax, guarded inserts instead of `INSERT IGNORE`, no user variables); catalog rows are identical to the MySQL copy. The applied MySQL V25 was left untouched to preserve its Flyway checksum. A scratch-database probe confirmed MySQL `INSERT IGNORE` skips the role-1 grant safely on fresh installs before bootstrap.
- Added `OrganizationNavigationTest` (system-only navigation, platform-only grants yield an empty sidebar, platform components cannot be registered) and repaired `OrganizationServiceTest` for the new `snapshot(granted)` signature and admin guards. Organization tests pass; frontend production build passes; live checks confirm admin sees System Management with all six pages and a platform-only role gets an empty sidebar.

Known remaining gaps (pre-existing from the granular refactor, not addressed here):

- Most API tests still grant legacy coarse codes (`datasource.manage`, `workflow.manage`, …) and fail with 403 under the new catalog; `DatasetPolicyStoreTest`/`DatasetAccessTest` still use `dataset.read`.
- `BootstrapAdmin` uses MySQL-only `INSERT IGNORE`, which breaks its H2 tests.
- The test suite needs a refresh to the granular codes before it can gate changes again.
