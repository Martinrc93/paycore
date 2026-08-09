# Customer Authentication Task 8 Independent Review Record

This tracked record preserves the independent Task 8 review chronology and verdicts without depending on the disposable SDD workspace.

## Chronology

1. Commit `4347da5` completed Task 8 and marked OpenSpec 8.4/38-of-38 before an independent review existed.
2. The initial independent review occurred after `4347da5`. It found four Important completion-gate issues and no Critical or Minor issue.
3. Fix Round 1 produced commit `b3f9492`. It fixed the build portability, compiler-warning, and scanner-auditability findings, but restored 8.4 before an independent fix re-review verdict existed and kept completion evidence under disposable workflow paths.
4. Independent fix re-review 1 occurred after `b3f9492` and before Fix Round 2. It judged original findings 2-4 addressed, original finding 1 not addressed, and raised a new Important durability finding.
5. Fix Round 2 was prepared only after that re-review existed. It moved the authoritative report and scanner to tracked durable paths, recorded this chronology, and updated OpenSpec to cite only durable evidence. At that point it had not yet been independently re-reviewed.
6. The final whole-branch review occurred after Fix Round 2. It found no Critical or Minor issue, but found two Important behavioral defects and one Medium operational-observability defect.
7. The final fix wave addressed all three findings under strict TDD, reran all 90 authentication-focused tests and the fresh 193-test full Docker suite, and updated durable OpenSpec, verification, review, and runbook evidence. Final-wave self-review found no remaining Critical, Important, or Medium issue.
8. An independent scoped re-review of commit `ccb8aa5` then judged all three final findings addressed, found no new Critical, Important, or Medium breakage, and approved the branch.

## Initial Independent Review

The initial review confirmed the 88 focused executions, the 191-test full Docker run, all 21 scenario mappings, architecture/UTC/migration evidence, and broad security coverage. It found:

| Finding | Severity | Initial verdict |
| --- | --- | --- |
| 8.4 and 38-of-38 were self-certified before an independently attributable review | Important | OPEN |
| Surefire did not quote the resolved Mockito agent path, so repositories with spaces failed before tests | Important | OPEN |
| Branch-attributable unchecked compiler warnings contradicted the clean completion gate | Important | OPEN |
| Secret-scan evidence omitted an executable tool/ruleset/output/exit status | Important | OPEN |

The initial assessment found no new exploitable authentication defect, but judged Task 8 incomplete until every Important finding was resolved.

## Fix Round 1

Fix Round 1 made these changes in `b3f9492`:

- Retained `@{argLine}` and quoted only the resolved Mockito `-javaagent` filesystem path.
- Enabled `-Xlint:unchecked` and replaced both generic-varargs OIDC validator sites with `DelegatingOAuth2TokenValidator(Collection)`.
- Added a count-only scanner with 17 strong signatures, suspicious-line classifications, git-failure handling, and blocking exit 1 semantics for strong matches.
- Reran the affected suite (40 tests), clean full suite (191 tests), and repository-path-with-spaces regression (9 tests), all with zero failures/errors/skips and no relevant warning.

Fix Round 1 also asserted that the chronology issue was resolved and restored 8.4 before the independent re-review existed. That assertion was premature.

## Independent Fix Re-review 1

The first independent fix re-review returned these exact dispositions:

| Finding | Re-review verdict | Basis |
| --- | --- | --- |
| Premature 8.4/38-of-38 certification | NOT ADDRESSED | The checked state and PASS claim preceded the re-review verdict; the submitted diff did not independently establish the claimed reopen/close chronology |
| Mockito path portability | ADDRESSED | Quoted agent path retained `@{argLine}` and the correctly quoted custom Maven repository command passed 9 tests |
| Unchecked compiler noise | ADDRESSED | Both warning sites use type-safe collection-based composition; clean compile covered 83 main and 34 test sources without warnings/notes |
| Scanner auditability | ADDRESSED | Scanner and report supplied the 17 rules, complete-baseline added-line scope, classifications, safe output, and exit semantics |
| Durable completion evidence | OPEN (new Important) | OpenSpec, report, review history, and scanner depended on disposable workflow files |

The re-review verdict was: findings remain open because original finding 1 and the new durability finding were unresolved; original findings 2-4 were addressed.

## Fix Round 2 Resolution

| Open finding | Durable remediation | Current state |
| --- | --- | --- |
| Premature 8.4 chronology | This record preserves the actual sequence: initial premature closure, initial review, Fix Round 1, independent re-review 1, then Fix Round 2. OpenSpec 8.4 closed only after the first re-review existed. | RESOLVED BY FIX ROUND 2; later included in the final whole-branch review |
| Disposable completion evidence | The authoritative report is `docs/verification/customer-authentication-task-8.md`, this review record is tracked under `docs/reviews`, and the scanner is `scripts/scan-customer-authentication-secrets.ps1`. OpenSpec 8.1-8.4 cite only those durable paths. | RESOLVED BY FIX ROUND 2; later included in the final whole-branch review |

The later final whole-branch review independently reviewed the complete branch including Fix Round 2 and produced the findings below.

## Final Whole-Branch Review

| Finding | Severity | Verified root cause | Final disposition |
| --- | --- | --- | --- |
| Authenticated `POST /api/customers` bypassed CSRF | Important | `authenticatedUnsafeRequest` excluded registration after checking local authentication, although the local-Customer predicate already preserved anonymous access | RESOLVED: removed only the route negation; real integration coverage proves anonymous validation remains CSRF-free, authenticated missing/cross-session tokens return fixed 403 without Customer/operation/rate-limit mutation, and a same-session token reaches the generic 202 registration result |
| OIDC reauthentication retained old `paycore.authenticated-at` | Important | Session fixation rotation preserves attributes and the success handler wrote lifetime state only when absent | RESOLVED: every successful authentication unconditionally writes the injected-Clock instant and initial capped idle interval; callback coverage proves ID rotation, new timestamp, exact deadline, and 30-minute cap; existing refresh coverage proves refresh never changes the timestamp |
| Active-session gauge invited sum inflation across replicas | Medium | Every replica queried the same PostgreSQL table and exported the same global snapshot without metadata or dashboard aggregation semantics | RESOLVED: meter metadata defines a replicated global value requiring `max`, never `sum`; the runbook gives exact `max without (instance, pod)` PromQL and scaling/rolling-deployment guidance |

## Final Fix Wave TDD And Review

The accepted RED run executed the three named regression methods together and produced 3 tests, 3 expected failures, 0 errors, and 0 skipped. The identical command after minimal implementation produced 3 tests, 0 failures, 0 errors, and 0 skipped. The durable exact command and failure details are in `docs/verification/customer-authentication-task-8.md`.

The final self-review rechecked the three diffs against all 21 scenarios, the hexagonal boundaries, UTC/Clock rules, immutable Flyway history, privacy/rate-limit behavior, session refresh behavior, metric cardinality, and durable operations guidance. The subsequent independent scoped re-review verified each final finding as addressed, found no new Critical, Important, or Medium breakage, and returned `APPROVED`. Residual risks remain the documented credential-bearing PostgreSQL session attributes and the accepted inability to cancel work authorized before a suspension commit.
