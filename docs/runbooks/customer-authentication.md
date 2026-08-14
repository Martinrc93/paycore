# Customer Authentication Operations

## Security Preconditions

- Terminate TLS only at PayCore or a trusted reverse proxy. Production browser, callback, and Keycloak URLs must use HTTPS.
- Set `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK` only when the trusted proxy strips all client-supplied `Forwarded` and `X-Forwarded-*` headers before setting its own values. Otherwise leave the default `NONE`.
- Keep the SPA and BFF same-site. The externally visible origin used by the browser must exactly match the Keycloak client redirect and web origin.
- Use startup `--import-realm` only to create an absent realm. Keycloak 26.5.2 skips an existing realm during startup import; use the existing-realm procedure below for updates.
- Apply Flyway migrations and verify PostgreSQL connectivity before enabling authentication. Do not let Spring initialize the session schema.
- Keep login disabled until discovery, JWKS, Authorization Code + PKCE, database, cookie, cleanup, and rollback checks pass.
- Apply the verified-activation migration as a coordinated stop-the-world change. Old replicas must be stopped before V4 because they cannot read `PENDING_VERIFICATION` or safely write the new lifecycle.

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

The versioned `paycore-bff` client is confidential, enables only standard Authorization Code flow, requires PKCE S256, and allows exactly `http://localhost:8080/login/oauth2/code/paycore` plus origin `http://localhost:8080` for the reproducible local contract. Production provisioning must render those two non-secret local values to the exact HTTPS callback and origin above before accepting traffic. It must retain access-token lifetime 300 seconds, client/session idle lifetime 1800 seconds, and maximum lifetime 28800 seconds.

Do not place usable client secrets, passwords, cookies, authorization codes, access tokens, refresh tokens, ID tokens, claims, issuers, subjects, or token fragments in source control, command history, logs, metrics, traces, tickets, or dashboards.

## Realm Deployment And Updates

### Fresh Realm

1. Confirm `GET /admin/realms/paycore` returns HTTP 404 and take a database backup before provisioning.
2. Copy `deploy/keycloak/paycore-realm.json` to a mode-`0600` temporary deployment workspace. Render only the production redirect URI and web origin with an audited JSON tool; do not edit the versioned file.
3. Leave `${PAYCORE_OIDC_CLIENT_SECRET}` and the provisioner placeholder in the rendered JSON. Supply those values to the Keycloak process from the deployment secret store so Keycloak resolves them during import; never write their resolved values to the artifact.
4. Name the rendered file `paycore-realm.json`, place it in `/opt/keycloak/data/import`, and start Keycloak 26.5.2 once with `kc.sh start --import-realm`. Remove the temporary rendered file after startup.
5. Verify the realm and client through the admin API: exact issuer, redirect URI, web origin, confidential client, standard flow only, PKCE S256, disabled implicit/direct grants, and 300/1800/28800-second bounds. Complete discovery, JWKS, invalid-credential, and PKCE login gates before enabling PayCore login.

Startup import is not an update mechanism. If the realm already exists, Keycloak logs that it was skipped and leaves the old configuration unchanged.

### Existing Realm: Online Admin Update

Use this procedure for ordinary redirect, origin, flow, PKCE, or lifetime updates because it preserves users, identity links, credentials, and unrelated realm configuration.

1. Set `PAYCORE_AUTHENTICATION_ENABLED=false`, drain login/callback traffic, and take an encrypted PostgreSQL/Keycloak database backup.
2. Authenticate `kcadm.sh` to the exact production Keycloak 26.5.2 admin endpoint using an operator credential supplied outside command history. Install `age`, load `BACKUP_RECIPIENT` from protected deployment configuration, and set `AGE_IDENTITY_FILE` to a mode-`0400` identity file supplied outside shell history. Run every block below in Bash with `set -euo pipefail`; each block repeats that setting and checks the variables it consumes so a copied block cannot continue after a failed command, failed pipeline, or missing prerequisite.
3. Stream the pre-change representations directly into authenticated encryption. Do not redirect decrypted JSON to disk:

```bash
set -euo pipefail
umask 077
install -d -m 0700 /secure/paycore-admin-backup
: "${BACKUP_RECIPIENT:?missing age backup recipient}"
: "${AGE_IDENTITY_FILE:?missing age identity file}"

kcadm.sh get realms/paycore \
  | age --encrypt --recipient "${BACKUP_RECIPIENT}" \
      --output /secure/paycore-admin-backup/pre-paycore-realm.json.age
CLIENT_UUID="$(kcadm.sh get clients -r paycore -q clientId=paycore-bff --fields id --format csv --noquotes)"
kcadm.sh get "clients/${CLIENT_UUID}" -r paycore \
  | age --encrypt --recipient "${BACKUP_RECIPIENT}" \
      --output /secure/paycore-admin-backup/pre-paycore-bff.json.age
```

4. Verify `CLIENT_UUID` resolves exactly one client and prove both encrypted artifacts decrypt and identify the intended resources without writing plaintext:

```bash
set -euo pipefail
: "${CLIENT_UUID:?missing verified Keycloak client UUID}"
: "${AGE_IDENTITY_FILE:?missing age identity file}"

test "$(printf '%s\n' "${CLIENT_UUID}" | grep -c .)" -eq 1
age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/pre-paycore-realm.json.age \
  | jq -e '.realm == "paycore"' >/dev/null
age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/pre-paycore-bff.json.age \
  | jq -e --arg id "${CLIENT_UUID}" \
      '.id == $id and .clientId == "paycore-bff"' >/dev/null
```

5. Create encrypted update artifacts through audited `jq` transforms. Set `PAYCORE_WEB_ORIGIN` to the exact externally visible HTTPS origin; it is an operator variable, not an additional PayCore application property. Delete `.secret` from the client representation so this update cannot rotate or overwrite the deployment secret. Pipe each transform directly back through `age`, then decrypt and validate the controlled fields without creating a plaintext file:

```bash
set -euo pipefail
: "${BACKUP_RECIPIENT:?missing age backup recipient}"
: "${AGE_IDENTITY_FILE:?missing age identity file}"
: "${CLIENT_UUID:?missing verified Keycloak client UUID}"
: "${PAYCORE_OIDC_REDIRECT_URI:?missing exact HTTPS redirect URI}"
: "${PAYCORE_WEB_ORIGIN:?missing exact HTTPS web origin}"

age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/pre-paycore-realm.json.age \
  | jq '.accessTokenLifespan = 300
        | .ssoSessionIdleTimeout = 1800
        | .ssoSessionMaxLifespan = 28800' \
  | age --encrypt --recipient "${BACKUP_RECIPIENT}" \
      --output /secure/paycore-admin-backup/paycore-realm-update.json.age

age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/pre-paycore-bff.json.age \
  | jq --arg redirect "${PAYCORE_OIDC_REDIRECT_URI}" \
       --arg origin "${PAYCORE_WEB_ORIGIN}" \
       'del(.secret)
        | .enabled = true
        | .publicClient = false
        | .serviceAccountsEnabled = false
        | .standardFlowEnabled = true
        | .implicitFlowEnabled = false
        | .directAccessGrantsEnabled = false
        | .redirectUris = [$redirect]
        | .webOrigins = [$origin]
        | .attributes["pkce.code.challenge.method"] = "S256"
        | .attributes["access.token.lifespan"] = "300"
        | .attributes["client.session.idle.timeout"] = "1800"
        | .attributes["client.session.max.lifespan"] = "28800"' \
  | age --encrypt --recipient "${BACKUP_RECIPIENT}" \
      --output /secure/paycore-admin-backup/paycore-bff-update.json.age

age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/paycore-realm-update.json.age \
  | jq -e '.realm == "paycore"
           and .accessTokenLifespan == 300
           and .ssoSessionIdleTimeout == 1800
           and .ssoSessionMaxLifespan == 28800' >/dev/null
age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/paycore-bff-update.json.age \
  | jq -e --arg id "${CLIENT_UUID}" \
      '.id == $id and .clientId == "paycore-bff" and (has("secret") | not)' >/dev/null
```
6. Decrypt each update artifact directly into the supported Keycloak Admin CLI standard-input form:

```bash
set -euo pipefail
: "${AGE_IDENTITY_FILE:?missing age identity file}"
: "${CLIENT_UUID:?missing verified Keycloak client UUID}"

age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/paycore-realm-update.json.age \
  | kcadm.sh update realms/paycore -f -
age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/paycore-bff-update.json.age \
  | kcadm.sh update "clients/${CLIENT_UUID}" -r paycore -f -
```

7. Re-fetch both representations and compare only the controlled fields by streaming the `kcadm.sh get` output and decrypted update artifact through `jq`; do not materialize either plaintext representation. Repeat the decryption/identity checks from step 4, then verify discovery/JWKS and a PKCE login with PayCore login still disabled for general traffic.
8. Re-enable login only after every pre/post assertion passes. Retain the encrypted pre-change artifacts according to the backup policy and securely delete encrypted update artifacts when no longer needed.

Rollback the admin update by disabling login and streaming the encrypted pre-change representations directly to the same endpoints. Remove `.secret` in the client pipeline; client-secret rollback follows the separate maintenance procedure below. Re-run the decryption, resource-identity, controlled-field, discovery/JWKS, and PKCE checks before re-enabling login:

```bash
set -euo pipefail
: "${AGE_IDENTITY_FILE:?missing age identity file}"
: "${CLIENT_UUID:?missing verified Keycloak client UUID}"

age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/pre-paycore-realm.json.age \
  | kcadm.sh update realms/paycore -f -
age --decrypt --identity "${AGE_IDENTITY_FILE}" \
    /secure/paycore-admin-backup/pre-paycore-bff.json.age \
  | jq 'del(.secret)' \
  | kcadm.sh update "clients/${CLIENT_UUID}" -r paycore -f -
```

### Existing Realm: Offline Full Import

Use offline replacement only for a deliberately complete realm migration, not for routine client updates. The versioned scaffold is not a complete export of runtime users and must never replace an existing production realm directly.

1. Disable PayCore login, drain traffic, stop every Keycloak node connected to the database, and take an encrypted database snapshot.
2. With all nodes stopped, create a complete pre-change export using the same Keycloak 26.5.2 build:

```bash
kc.sh export --dir /secure/pre-change-export --realm paycore --users realm_file
```

3. Copy the complete export to a protected workspace and patch only the controlled realm/client fields. Preserve users, credentials, roles, components, identity links, and all unrelated configuration. Supply secret placeholders from the secret store; do not persist resolved values.
4. Because `--users realm_file` embeds users in the realm representation, require the complete export at `/secure/pre-change-export/paycore-realm.json` and no separate `paycore-users-*.json` or `paycore-users.json` files. Validate that exact file set, ownership, checksums, user count, and backup restore readiness. The rendered complete export must likewise contain `paycore-realm.json` with its users embedded.
5. While every node remains stopped, run the supported offline replacement:

```bash
kc.sh import --dir /secure/rendered-complete-export --override true
```

6. Start one node, perform all realm/client/user-count and authentication pre/post checks, then start the remaining nodes. Re-enable PayCore login last.

Rollback by stopping every node again and importing `/secure/pre-change-export` with `--override true`, then repeating the same integrity and authentication checks. Never run offline export/import while a Keycloak node is serving the same database.

## Secret And Signing-Key Rotation

### Client Secret

The versioned realm does not configure a Client Secret Rotation client policy or rotated-secret expiry. Ordinary secret regeneration therefore has no overlap guarantee and can invalidate the previous secret immediately. Always use this disabled-login maintenance path:

1. Set `PAYCORE_AUTHENTICATION_ENABLED=false` on every instance, drain login/callback traffic, and invalidate PayCore sessions when the deployment cannot preserve them safely across the coordinated restart.
2. Confirm no old PayCore instance can exchange an authorization code.
3. Regenerate the `paycore-bff` secret through the Keycloak 26.5.2 admin client-secret endpoint. Send the response directly to the deployment secret store; do not print it or place it in a realm file, shell history, ticket, or temporary plaintext file.
4. Atomically promote the new `PAYCORE_OIDC_CLIENT_SECRET`, restart every PayCore instance, and keep login disabled.
5. Run discovery/JWKS checks and one controlled Authorization Code + PKCE login/code exchange using the new secret on every deployment pool.
6. Re-enable login only after all instances use the new secret and login/refresh metrics remain stable.

Never print either secret during comparison or troubleshooting. A client-secret rotation does not require a realm JSON commit.

If verification fails, keep login disabled. Because ordinary regeneration provides no overlap, do not assume the old secret still works. Generate another secret, atomically update Keycloak and the deployment secret again, restart every instance, and repeat verification before restoring traffic.

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
| `paycore.authentication.customer.verification.activations` | `reason=verified_email` | A pending Customer was activated by validated OIDC evidence. |
| `paycore.authentication.customer.verification.denials` | `reason=email_unverified` | Pending activation was denied because verified-email evidence was false or absent. |
| `paycore.authentication.customer.verification.conflicts` | `reason=status_changed` | A pending activation lost a compare-and-set race with another Customer status change. |
| `paycore.authentication.session.revocations` | `scope=current|all` | Revocation operations requested. |
| `paycore.authentication.sessions.revoked` | `scope=current|all` | Session rows actually deleted by those operations. |
| `paycore.authentication.session.cleanup.runs` | `reason=scheduled` | Bounded expired-session cleanup executions. |
| `paycore.authentication.sessions.expired` | `reason=expired` | Expired session rows actually deleted; attributes are removed by FK cascade. |
| `paycore.authentication.sessions.active` | none | Replicated global PostgreSQL count of rows whose expiry is not in the past. Every replica exports the same database-wide snapshot; never sum replica copies. |

Operational log events contain only fixed `category` and `reason` values. Do not add Customer IDs, issuer/subject, request headers, cookies, credentials, claims, authorization codes, tokens, token fragments, exception messages, or remote response bodies.

Monitor current and expired backlog counts directly without selecting session IDs or attributes:

For Prometheus-compatible dashboards and alerts, aggregate the replicated active-session gauge with `max`, not `sum`. Preserve environment/cluster/job labels while removing only replica identity labels; the exact baseline query is:

```promql
max without (instance, pod) (paycore_authentication_sessions_active)
```

Do not use `sum(paycore_authentication_sessions_active)`: it reports approximately the database-global count multiplied by the number of scraped replicas. Scaling and rolling deployments change that multiplier and can create false jumps. During a rollout, old and new replicas can also be scraped at slightly different instants; keep all replica series until the rollout completes and use `max` throughout. If the monitoring system uses replica labels other than `instance` or `pod`, add only those labels to `without`; do not remove labels that distinguish environments, clusters, or database deployments.

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

Each application replica registers one cleanup schedule. PostgreSQL `FOR UPDATE SKIP LOCKED` coordinates replicas so concurrent runs claim disjoint bounded batches without double counting. Alert if cleanup runs stop, expired backlog grows across multiple cleanup intervals, cleanup consistently reaches the configured batch size, refresh failures spike, or all-session deletion count changes unexpectedly. Increase cleanup frequency or batch size only within the validated bounds and after checking database load.

## Incompatible Deployments

Session attributes contain serialized Spring Security and OAuth client types. Before a deployment that cannot deserialize the current version:

1. Disable login and drain protected traffic.
2. Invalidate all PayCore sessions with `DELETE FROM spring_session`; FK cascade removes every serialized attribute and server-side token.
3. Confirm both session tables contain zero rows without selecting attribute values.
4. Deploy the incompatible version, run a fresh login smoke test, and then re-enable login.

Do not attempt to transform or log serialized token attributes. Compatible rolling deployments must prove old and new instances can read, refresh, and revoke the same session before rollout.

## Rollout Gates

1. Stop every old PayCore application replica and registration worker. Do not admit traffic from an old process after V4.
2. Set `PAYCORE_AUTHENTICATION_ENABLED=false`, drain login/callback traffic, and take an encrypted PostgreSQL backup.
3. Record Customer status and active-session counts before migration:

   ```sql
   SELECT status, count(*) AS customers FROM customers GROUP BY status ORDER BY status;
   SELECT count(*) AS active_sessions FROM spring_session
   WHERE expiry_time >= (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint;
   ```

4. Apply Flyway V4 with authentication disabled. Verify all former `ACTIVE` Customers are `PENDING_VERIFICATION`, their sessions are gone, no `ACTIVE` Customer survived unexpectedly, and every other status was preserved.
5. Create an absent realm with fresh startup import or update an existing realm with the supported admin/offline procedure above. Verify the confidential `paycore-bff` client, exact HTTPS redirect/origin, standard flow only, PKCE S256, bounded lifetimes, and deployment secret.
6. Verify TLS certificates, exact issuer discovery, JWKS reachability, trusted proxy stripping, and same-site external URLs from every PayCore instance.
7. Run the real Keycloak authentication contract, focused observability tests, prior OIDC/security/session regressions, architecture tests, and the full test suite with Docker.
8. Verify database least privilege, encrypted connection, encrypted backup, restore access controls, and cleanup queries.
9. Enable login for a canary pool. Require forced OIDC reauthentication; only validated `email_verified=true` evidence restores migrated Customers to `ACTIVE`.
10. Complete login, refresh, protected request, CSRF-protected local logout, invalid credentials, Customer denial, and session invalidation checks.
11. Confirm metrics/logs contain only fixed categories and reasons and no representative secret values.
12. Expand traffic while monitoring verification activations, unverified denials, activation conflicts, login/refresh failures, active sessions, revocation counts, cleanup runs, and expired backlog.

## Rollback

1. Set `PAYCORE_AUTHENTICATION_ENABLED=false` first so no new browser login starts.
2. Remove authentication traffic from the affected deployment and invalidate all PayCore sessions with `DELETE FROM spring_session`; verify FK-cascaded attributes are zero.
3. Do not revert, delete, or edit Flyway migrations. After V4, the previous application is incompatible with `PENDING_VERIFICATION` and must not be restarted.
4. Use a forward fix or restore the matching pre-migration database backup with the matching application version. Never bulk-restore migrated Customers to `ACTIVE` without verified evidence.
5. Roll back non-secret realm configuration only when issuer, client, redirect, signing keys, and persisted identity links remain compatible.
6. Do not remove an overlapping signing key until all tokens that require it are gone. Client secrets have no assumed overlap and must use the disabled-login maintenance procedure.
7. Verify login remains disabled, stale cookies receive HTTP 401, and cleanup/session counts stabilize before declaring rollback complete.
