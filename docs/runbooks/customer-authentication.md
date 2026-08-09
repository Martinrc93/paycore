# Customer Authentication Operations

## Security Preconditions

- Terminate TLS only at PayCore or a trusted reverse proxy. Production browser, callback, and Keycloak URLs must use HTTPS.
- Set `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK` only when the trusted proxy strips all client-supplied `Forwarded` and `X-Forwarded-*` headers before setting its own values. Otherwise leave the default `NONE`.
- Keep the SPA and BFF same-site. The externally visible origin used by the browser must exactly match the Keycloak client redirect and web origin.
- Import `deploy/keycloak/paycore-realm.json` into Keycloak 26.5.2 with deployment secrets supplied as environment values. Never persist rendered realm files containing secrets.
- Apply Flyway migrations and verify PostgreSQL connectivity before enabling authentication. Do not let Spring initialize the session schema.
- Keep login disabled until discovery, JWKS, Authorization Code + PKCE, database, cookie, cleanup, and rollback checks pass.

## Exact Configuration

The production values below are one coherent deployment contract. Replace `paycore.example` and `id.paycore.example` consistently for another environment; do not use wildcards.

| Environment variable | Required production value or purpose |
| --- | --- |
| `PAYCORE_AUTHENTICATION_ENABLED` | `true` only after rollout gates pass; `false` disables browser login. |
| `PAYCORE_AUTHENTICATION_SUCCESS_URI` | Exact same-site post-login path, for example `/`. |
| `PAYCORE_AUTHENTICATION_LOGOUT_PATH` | Exact local logout path `/bff/auth/logout`. This revokes only the current PayCore session and does not end Keycloak SSO. |
| `PAYCORE_OIDC_ISSUER_URI` | Exact issuer `https://id.paycore.example/realms/paycore`; no alternate hostname or trailing path. |
| `PAYCORE_OIDC_CLIENT_ID` | Exact confidential client ID `paycore-bff`. |
| `PAYCORE_OIDC_CLIENT_SECRET` | Confidential client secret from the deployment secret store. It must also render the realm import placeholder for that environment. |
| `PAYCORE_OIDC_REDIRECT_URI` | Exact callback `https://paycore.example/login/oauth2/code/paycore`. |
| `PAYCORE_AUTHENTICATION_SESSION_CLEANUP_DELAY` | Fixed cleanup delay from `1m` through `1h`; default `5m`. |
| `PAYCORE_AUTHENTICATION_SESSION_CLEANUP_INITIAL_DELAY` | Initial delay; default `5m` to avoid startup/shutdown churn. |
| `PAYCORE_AUTHENTICATION_SESSION_CLEANUP_BATCH_SIZE` | Maximum rows removed per run from `1` through `10000`; default `1000`. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `FRAMEWORK` only behind the trusted stripping proxy described above; otherwise `NONE`. |

The versioned `paycore-bff` client is confidential, enables only standard Authorization Code flow, requires PKCE S256, and allows exactly `http://localhost:8080/login/oauth2/code/paycore` plus origin `http://localhost:8080` for the reproducible local contract. Production provisioning must replace those two non-secret local values with the exact HTTPS callback and origin above before accepting traffic. It must retain access-token lifetime 300 seconds, client/session idle lifetime 1800 seconds, and maximum lifetime 28800 seconds.

Do not place usable client secrets, passwords, cookies, authorization codes, access tokens, refresh tokens, ID tokens, claims, issuers, subjects, or token fragments in source control, command history, logs, metrics, traces, tickets, or dashboards.

## Secret And Signing-Key Rotation

### Client Secret

1. Keep login enabled only if the deployment platform can update Keycloak and every PayCore instance as one controlled rotation.
2. Create a new Keycloak client secret using the supported client-secret rotation operation. Retain the previous secret during the deployment overlap when the configured Keycloak policy supports it.
3. Update `PAYCORE_OIDC_CLIENT_SECRET` in the secret store, roll PayCore instances, and verify discovery plus a new PKCE login on every deployment pool.
4. Remove or revoke the previous secret after no old instance remains and the login-failure rate is stable.
5. If overlap is unavailable, disable login, invalidate PayCore sessions, rotate both sides in a maintenance window, verify, and then re-enable login.

Never print either secret during comparison or troubleshooting. A client-secret rotation does not require a realm JSON commit.

### Realm Signing Keys

1. Add a generated RSA signing-key provider through Keycloak's admin component API with `enabled=true`, `active=true`, and a higher priority.
2. Verify new tokens use the new `kid` while JWKS still publishes the old and new public keys.
3. Retain the old key enabled but passive for longer than the maximum lifetime of every token it signed plus JWKS cache propagation time.
4. Disable and later remove the old provider only after old tokens can no longer be accepted.

The Keycloak 26.5.2 authentication contract exercises this exact overlap and validates both old and new token signatures from the transitioned JWKS.

## Database Protection

- Give the runtime role only the existing application privileges and `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on `spring_session` and `spring_session_attributes`. Do not grant schema ownership, DDL, superuser, replication, or bypass-RLS privileges.
- Use a separate migration role for Flyway DDL. Remove it from the runtime deployment after migrations complete.
- Restrict network access to PayCore instances and approved administrative paths. Require encrypted PostgreSQL connections with certificate validation.
- Encrypt database volumes, snapshots, replicas, exports, and backups with managed keys. Restrict backup restore and key access independently from application access.
- Treat session backups as credential-bearing because serialized attributes contain OAuth tokens. Apply retention limits, audit restores, and securely expire obsolete backups.
- Test encrypted restore procedures without copying session attributes into diagnostics.

## Monitoring

Alert on rate changes, sustained non-zero failures, cleanup backlog, and unexpected mass revocation. The authentication meters have fixed low-cardinality tags only:

| Meter | Fixed tags | Meaning |
| --- | --- | --- |
| `paycore.authentication.login.failures` | `reason=authentication_rejected` | OIDC callback or local login rejection. |
| `paycore.authentication.refresh.failures` | `reason=refresh_rejected` | Server-side token renewal failed and the local session was invalidated. |
| `paycore.authentication.customer.access.denials` | `reason=customer_unavailable` | A linked login or protected session could not obtain active Customer access. |
| `paycore.authentication.session.revocations` | `scope=current|all` | Revocation operations requested. |
| `paycore.authentication.sessions.revoked` | `scope=current|all` | Session rows actually deleted by those operations. |
| `paycore.authentication.session.cleanup.runs` | `reason=scheduled` | Bounded expired-session cleanup executions. |
| `paycore.authentication.sessions.expired` | `reason=expired` | Expired session rows actually deleted; attributes are removed by FK cascade. |

Operational log events contain only fixed `category` and `reason` values. Do not add Customer IDs, issuer/subject, request headers, cookies, credentials, claims, authorization codes, tokens, token fragments, exception messages, or remote response bodies.

Monitor current and expired backlog counts directly without selecting session IDs or attributes:

```sql
SELECT count(*) AS active_sessions
FROM spring_session
WHERE expiry_time >= (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint;
```

```sql
SELECT count(*) AS expired_session_backlog
FROM spring_session
WHERE expiry_time < (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint;
```

Alert if cleanup runs stop, expired backlog grows across multiple cleanup intervals, cleanup consistently reaches the configured batch size, refresh failures spike, or all-session deletion count changes unexpectedly. Increase cleanup frequency or batch size only within the validated bounds and after checking database load.

## Incompatible Deployments

Session attributes contain serialized Spring Security and OAuth client types. Before a deployment that cannot deserialize the current version:

1. Disable login and drain protected traffic.
2. Invalidate all PayCore sessions with `DELETE FROM spring_session`; FK cascade removes every serialized attribute and server-side token.
3. Confirm both session tables contain zero rows without selecting attribute values.
4. Deploy the incompatible version, run a fresh login smoke test, and then re-enable login.

Do not attempt to transform or log serialized token attributes. Compatible rolling deployments must prove old and new instances can read, refresh, and revoke the same session before rollout.

## Rollout Gates

1. Apply Flyway migrations with `PAYCORE_AUTHENTICATION_ENABLED=false`; never edit or revert released migrations.
2. Provision Keycloak 26.5.2 with the confidential `paycore-bff` client, exact HTTPS redirect/origin, standard flow only, PKCE S256, bounded lifetimes, and deployment secret.
3. Verify TLS certificates, exact issuer discovery, JWKS reachability, trusted proxy stripping, and same-site external URLs from every PayCore instance.
4. Run the real Keycloak authentication contract, focused observability tests, prior OIDC/security/session regressions, architecture tests, and the full test suite with Docker.
5. Verify database least privilege, encrypted connection, encrypted backup, restore access controls, and cleanup queries.
6. Enable login for a canary pool. Complete login, refresh, protected request, CSRF-protected local logout, invalid credentials, Customer denial, and session invalidation checks.
7. Confirm metrics/logs contain only fixed categories and reasons and no representative secret values.
8. Expand traffic while monitoring login/refresh failures, active sessions, revocation counts, cleanup runs, and expired backlog.

## Rollback

1. Set `PAYCORE_AUTHENTICATION_ENABLED=false` first so no new browser login starts.
2. Remove authentication traffic from the affected deployment and invalidate all PayCore sessions with `DELETE FROM spring_session`; verify FK-cascaded attributes are zero.
3. Do not revert, delete, or edit Flyway migrations. Leave session tables unused if the previous application does not authenticate Customers.
4. Roll back application or non-secret realm configuration only when issuer, client, redirect, signing keys, and persisted identity links remain compatible.
5. Do not remove an overlapping signing key or previous client secret until all instances and tokens that require it are gone.
6. Verify login remains disabled, stale cookies receive HTTP 401, and cleanup/session counts stabilize before declaring rollback complete.
