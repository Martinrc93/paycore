# Customer Authentication Task 8 Verification

## Status And Chronology

- OpenSpec Task 8 is 38/38 after Fix Round 2 prepared durable completion evidence.
- Commit `4347da5` initially completed Task 8 before an independent review existed.
- The initial independent review then found four Important findings.
- Fix Round 1 commit `b3f9492` addressed build portability, compiler warnings, and scanner auditability, but restored 8.4 before an independent fix re-review and stored evidence in a disposable workflow directory.
- Independent fix re-review 1 existed before Fix Round 2. It confirmed the three technical fixes and left chronology plus evidence durability open.
- Fix Round 2 closes 8.4 only after that re-review existed by establishing this report, `docs/reviews/customer-authentication-task-8.md`, and `scripts/scan-customer-authentication-secrets.ps1` as tracked durable artifacts.
- Fix Round 2 has not yet been independently re-reviewed.

The detailed independent verdict history is preserved in `docs/reviews/customer-authentication-task-8.md`.

## Focused Verification (8.1)

These commands ran from `P:\dev\paycore` on 2026-08-09 with Docker Desktop available.

| Area | Exact command | Result |
| --- | --- | --- |
| Domain/application | `.\mvnw.cmd --% -Dtest=ResolveCustomerAccessServiceTest,ChangeCustomerStatusServiceTest,SessionLifetimePolicyTest test` | 19 tests, 0 failures, 0 errors, 0 skipped |
| PostgreSQL/Flyway/session/concurrency | `.\mvnw.cmd --% -Dtest=CustomerAccessPersistenceAdapterTest,SpringSessionRevocationAdapterTest,ConcurrentStatusRevocationTest test` | 10 tests, 0 failures, 0 errors, 0 skipped |
| Spring Security/browser/OIDC | `.\mvnw.cmd --% -Dtest=AuthenticationConfigurationTest,BrowserSecurityTest,OidcLoginSecurityTest,ProtectedSessionSecurityTest test` | 48 tests, 0 failures, 0 errors, 0 skipped |
| Keycloak 26.5.2 contract | `.\mvnw.cmd -Dtest=KeycloakAuthenticationContractTest test` | 1 test, 0 failures, 0 errors, 0 skipped |
| Observability/cleanup | `.\mvnw.cmd --% -Dtest=AuthenticationObservabilityTest,ExpiredSessionCleanupTest test` | 8 tests, 0 failures, 0 errors, 0 skipped |
| Architecture | `.\mvnw.cmd -Dtest=IdentityArchitectureTest test` | 2 tests, 0 failures, 0 errors, 0 skipped |

Total: 88 focused executions, 0 failures, 0 errors, 0 skipped. PostgreSQL runs freshly validated and applied exactly V1 followed by V2 without stale scheduler, connection, or Testcontainers teardown errors.

## Full Verification (8.2)

| Exact command | Result |
| --- | --- |
| `.\mvnw.cmd test` | 191 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS |

The run used PostgreSQL 17 and Keycloak 26.5.2 containers and emitted no Mockito self-attachment/dynamic-agent warning, authentication-test deprecation warning, or stale cleanup/Testcontainers error.

## Scenario Evidence (8.3)

All 21 OpenSpec scenarios were checked against named implementation and executable evidence.

| Scenario | Implementation/test evidence | Verdict |
| --- | --- | --- |
| Customer starts login | `AuthenticationSecurityConfiguration` uses Authorization Code plus `withPkce()`; `OidcLoginSecurityTest.loginInitiationUsesStateNonceAndS256PkceWithoutExposingTokens` | PASS |
| Identity provider rejects credentials | `KeycloakAuthenticationContractTest.importedRealmCompletesPkceLoginRejectsBadCredentialsAndOverlapsSigningKeys`; invalid credentials create no user session/code | PASS |
| Active registered Customer completes login | `CustomerOidcAuthenticationSuccessHandler`; `OidcLoginSecurityTest.successfulCallbackRotatesSessionAndPersistsOnlyTheLocalCustomerIdentity` | PASS |
| Session identifier is renewed after login | Spring Security `changeSessionId()` and callback test compare pre/post IDs | PASS |
| Linked active Customer is resolved | `ResolveCustomerAccessService`; exact issuer/subject unit and PostgreSQL adapter tests | PASS |
| External identity is not linked | `OidcLoginSecurityTest.unknownIdentityReceivesSanitizedForbiddenWithoutAnAcceptedSession` | PASS |
| Linked Customer is not active | Parameterized inactive-status unit/login tests return the same fixed 403 | PASS |
| Active Customer uses a valid session | `CustomerStatusFilter`; `ProtectedSessionSecurityTest.activeRequestRefreshesOnlyServerSideWithoutChangingAuthenticatedAt` | PASS |
| Customer became inactive | `CustomerStatusFilter`; `statusIsReloadedAndFirstInactiveRequestRevokesAllSessionsThenReuseIsUnauthorized` | PASS |
| Revoked Customer session is reused | The protected-session regression proves first 403 then stale-cookie 401 | PASS |
| Session is absent or invalid | `JsonAuthenticationEntryPoint`; browser/protected tests assert fixed `{"code":"unauthorized"}` | PASS |
| Session exceeds idle timeout | 30-minute JDBC session limit; exact repository inactivity boundary test | PASS |
| Session reaches absolute lifetime | `SessionLifetimePolicy`, `SessionLifetimeFilter`, `AbsoluteExpirySessionRepository`; exact/fractional boundary tests | PASS |
| Access token expires during a valid local session | `OAuth2RefreshFilter`; server-only renewal persists fixed authenticated-at | PASS |
| Identity provider rejects token renewal | Refresh failure removes authorized client and invalidates the session; rejection regression test | PASS |
| Customer logs in from two devices | `SpringSessionRevocationAdapterTest.savesTwoIndependentConcurrentSessionsForOneCustomer` | PASS |
| Customer is suspended with multiple sessions | Transactional status change and principal-index revocation; concurrent suspension/blocking tests | PASS |
| State-changing request has a valid CSRF token | Session-backed CSRF repository; valid unsafe request test | PASS |
| CSRF token is missing or invalid | Browser test proves missing, invalid, and cross-session 403 with no mutation | PASS |
| Customer logs out from one device | Current-session and authorized-client removal plus exact cookie expiry; other session remains active | PASS |
| Logout request is replayed | `BrowserSecurityTest.logoutDeletesOnlyCurrentPostgresSessionAndTokensExpiresExactCookieAndCannotResurrect` | PASS |

## Architecture, ADR, Migration, And Task Evidence

| Scope | Evidence | Verdict |
| --- | --- | --- |
| ADR-0001 modular monolith | Authentication remains in `identity`; no cross-module internal access; `IdentityArchitectureTest` passes | PASS |
| ADR-0002 hexagonal dependencies | Domain/application stay framework-free; Spring/OIDC/JDBC remain infrastructure adapters; two architecture rules pass | PASS |
| ADR-0004 UTC/Clock | Lifetime/status/cleanup use injected `Clock` and `Instant`; no production `Date`, `Calendar`, `LocalDateTime`, or direct wall-clock call was introduced | PASS |
| V1 immutability | `git diff c886f6f -- src/main/resources/db/migration/V1__create_customer_registration.sql` produced no output | PASS |
| V2 ordering | V2 is additive, supplies session/attribute tables and expiry/principal indexes, and fresh Flyway logs apply V1 before V2 | PASS |
| Operational evidence | `docs/runbooks/customer-authentication.md` covers HTTPS/proxy trust, secret handling, encrypted DB/backups, rotation, cleanup, incompatible deployment, rollout, and rollback | PASS |
| Tasks 1.1-1.4 | Prerequisite linkage, Boot-managed dependencies, Keycloak pin/config, architecture rules | PASS |
| Tasks 2.1-2.4 | Exact issuer/subject, inactive/unknown/email behavior, framework-free model/use case, revocation port | PASS |
| Tasks 3.1-3.5 | V1 linkage, V2 schema, PostgreSQL adapter, 30-minute/principal indexing, expiration/revocation/cleanup | PASS |
| Tasks 4.1-4.6 | State/nonce/PKCE/callback/fixation, OIDC validation, local resolution, server-only tokens, cookie, refresh | PASS |
| Tasks 5.1-5.6 | Clock boundaries, repository cap, per-request status, all-session revocation, races | PASS |
| Tasks 6.1-6.5 | Allowlist, session CSRF, cross-session denial, local logout, fixed 401/403 disclosure | PASS |
| Tasks 7.1-7.4 | Realm contract, real Keycloak/key overlap, observability/cleanup, runbook | PASS |
| Tasks 8.1-8.4 | Exact durable verification, requirements evidence, independent verdict chronology, Fix Round 2 durability remediation | PASS; Fix Round 2 not independently re-reviewed yet |

The in-flight suspension latch is not load-bearing. The contract requires requests starting after the status transition commits to be denied; `ConcurrentStatusRevocationTest.suspensionRacesWithAnInFlightRequestButNoRequestStartingAfterCommitCanSucceed` proves that boundary.

## Security Evidence (8.4)

| Review area | Evidence and assessment | Verdict |
| --- | --- | --- |
| Credential/token leakage | Passwords remain in Keycloak; OAuth tokens remain in server-side authorized-client session; response/cookie/log assertions use representative sentinels | PASS |
| Session fixation | Spring Security changes the ID at authentication and callback test proves rotation | PASS |
| PKCE/state/nonce/audience/issuer | S256, tampered state, mismatched nonce, wrong audience, default issuer/time validation, and real Keycloak contract are covered | PASS |
| CSRF bypass/cross-session | Unsafe requests require session CSRF; missing/invalid/cross-session values return 403 without mutation; logout requires CSRF | PASS |
| Cookie scope | `__Host-paycore-session` is Secure, HttpOnly, SameSite=Lax, Path=/, without Domain; asserted at browser boundary | PASS |
| Provider/local privilege boundary | BFF has no service account/direct/implicit flow; provisioner is separate; application consumes validated issuer/subject and local Customer state | PASS |
| Refresh/lifetime/status races | Rejection removes tokens/session; refresh does not move authenticated-at; absolute check precedes status/refresh; post-commit status race is deterministic | PASS |
| Cleanup/metrics/log cardinality | Bounded transactional cleanup; fixed tags; count-only gauge; leakage assertions cover logs | PASS |
| Failure disclosure | Fixed JSON 401/403 has no linkage/status/OIDC/token/cryptographic detail; invalid provider credentials never reach PayCore | PASS |
| Logout/session independence | Current authorized client/session/cookie are removed while another device remains active; replay is 401 | PASS |

The initial independent review found no exploitable authentication defect. Its four Important findings were completion-process/build-evidence defects; their verdict history and resolutions are in `docs/reviews/customer-authentication-task-8.md`.

## Fix Round 1 Evidence

| Check | Exact command | Result |
| --- | --- | --- |
| Affected configuration/security tests | `.\mvnw.cmd --% -Dtest=AuthenticationConfigurationTest,OidcLoginSecurityTest,ProcessRegistrationServiceTest test` | 40 tests, 0 failures, 0 errors, 0 skipped |
| Clean full suite | `.\mvnw.cmd clean test` | 191 tests, 0 failures, 0 errors, 0 skipped; 83 main and 34 test sources compiled without warning/note |
| Maven repository path containing spaces | `.\mvnw.cmd --% -Dmaven.repo.local="C:\Users\marti\AppData\Local\Temp\opencode\maven repo with spaces" -Dtest=ProcessRegistrationServiceTest test` | 9 tests, 0 failures, 0 errors, 0 skipped; no Mockito agent/CDS warning |

Before the Surefire fix, the identical correctly quoted Maven property reached Surefire but the unquoted agent path was truncated at the space, ran 0 tests, and exited 1. Fix Round 1 retained `@{argLine}` and changed only the resolved agent path quoting. `-Xlint:unchecked` identified generic-array warnings in production and test OIDC validator composition; both now use the type-safe `DelegatingOAuth2TokenValidator(Collection)` constructor.

## Fix Round 2 Durable Secret Scans

Tool: `scripts/scan-customer-authentication-secrets.ps1`. It scans added lines in the complete `git diff --unified=0 c886f6f --`, never prints candidate values, exits 1 when a strong signature is present, and uses suspicious mode as count-only manual classification with exit 0.

The 17 strong rules cover AWS, GitHub, GitLab, Slack, Stripe, Google, SendGrid, npm, Twilio, private-key headers, HTTP Bearer/Basic credentials, JWTs, and credential-bearing PostgreSQL/MySQL/MongoDB URLs. Signatures are split in this prose so the report does not match its own scanner.

Strong command and exact output:

```powershell
& ".\scripts\scan-customer-authentication-secrets.ps1" -Mode Strong -Baseline c886f6f
# baseline=c886f6f
# added_lines_scanned=6745
# strong_rules=17
# strong_matches=0
# exit=0
```

Suspicious command and exact count-only output:

```powershell
& ".\scripts\scan-customer-authentication-secrets.ps1" -Mode Suspicious -Baseline c886f6f
# baseline=c886f6f
# added_lines_scanned=6745
# suspicious_pattern=(?i)(password|passwd|pwd|secret|token|api[_-]?key|authorization|bearer|cookie|credential|private[_-]?key)
# deployment_placeholder=2
# deployment_literal=9
# production_source=65
# test_fixture=370
# documentation_or_spec=195
# scanner_rules=3
# exit=0
```

Deployment placeholders are environment substitutions; deployment literals are non-secret property/lifetime names; production-source matches are identifiers or sanitized event/error vocabulary; test fixtures are disposable sentinels; documentation/spec matches are requirements and audit prose; scanner-rule matches are the ruleset itself. No real credential is reproduced in this report.

## Fix Round 2 Verification Scope

Fix Round 2 changes only documentation, OpenSpec/plan references, and the scanner's tracked path/classification identity. It does not change application code, tests, Maven configuration, deployment configuration, or runtime behavior. Therefore the 40 affected tests, 191-test clean full suite, and 9-test path regression from Fix Round 1 were not rerun; the durable scanner, strict OpenSpec validation, and repository diff checks were rerun for this path/documentation-only round.

## Remaining Risks

- PostgreSQL session attributes contain serialized OAuth tokens by design; the runbook requires least privilege, encrypted transport/storage/backups, bounded retention, and controlled incompatible-deployment invalidation.
- Revocation cannot cancel work authorized before a suspension commit; every request starting after commit is deterministically denied as specified.
- The fake provider converts `Instant` to `java.util.Date` only at a Nimbus test-fixture boundary; production lifetime/status/cleanup remains `Instant` plus injected `Clock`.
- Fix Round 2 has not yet received an independent re-review.
