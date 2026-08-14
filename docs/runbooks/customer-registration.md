# Customer Registration Operations

## Preconditions

- PostgreSQL migrations must be applied and Hibernate schema validation must pass.
- Import `deploy/keycloak/paycore-realm.json` into Keycloak 26.5.2 with
  `PAYCORE_KEYCLOAK_PROVISIONER_CLIENT_SECRET` and `PAYCORE_REGISTRATION_REDIRECT_URI` set.
- Configure Keycloak SMTP and verify delivery of `VERIFY_EMAIL` and `UPDATE_PASSWORD` actions.
- The `paycore-provisioner` service account must have exactly the `manage-users` realm-management role.
- Keep the registration endpoint and worker disabled until database, Keycloak, SMTP, and contract checks pass.
- `PENDING_VERIFICATION` is the expected post-provisioning state. Registration completion must never write `ACTIVE`.

## Configuration

Required secrets and endpoints are supplied through environment variables. Never place their values in source control or logs.

| Environment variable | Purpose |
| --- | --- |
| `PAYCORE_REGISTRATION_ENABLED` | Enables the public registration endpoint. |
| `PAYCORE_REGISTRATION_WORKER_ENABLED` | Enables asynchronous provisioning. |
| `PAYCORE_REGISTRATION_IDEMPOTENCY_CURRENT_VERSION` | Version used for new keyed idempotency digests. |
| `PAYCORE_REGISTRATION_IDEMPOTENCY_SECRETS` | Comma-separated `version=Base64Key` key ring. |
| `PAYCORE_REGISTRATION_RATE_LIMIT_SECRET` | Key for source/email rate-limit digests. |
| `PAYCORE_REGISTRATION_KEYCLOAK_BASE_URL` | Keycloak base URL. |
| `PAYCORE_REGISTRATION_KEYCLOAK_REALM` | Realm name. |
| `PAYCORE_REGISTRATION_KEYCLOAK_ISSUER` | Stable issuer stored in identity links. |
| `PAYCORE_REGISTRATION_KEYCLOAK_CLIENT_ID` | Provisioning service-account client ID. |
| `PAYCORE_REGISTRATION_KEYCLOAK_CLIENT_SECRET` | Provisioning service-account secret. |
| `PAYCORE_REGISTRATION_KEYCLOAK_REDIRECT_URI` | Exact post-action redirect allowed by the realm. |
| `PAYCORE_REGISTRATION_KEYCLOAK_CONNECT_TIMEOUT` | Per-request connection timeout; default `5s`. |
| `PAYCORE_REGISTRATION_KEYCLOAK_READ_TIMEOUT` | Per-request response timeout; default `30s`. |
| `PAYCORE_REGISTRATION_WORKER_ALERT_THRESHOLD` | Attempt count that starts sanitized retry alerts; default `5`. |
| `PAYCORE_REGISTRATION_WORKER_MAX_BATCH_SIZE` | Maximum operations processed per worker poll; default `20`. |
| `PAYCORE_REGISTRATION_CLEANUP_DELAY` | Delay between expired-operation/rate-limit cleanup runs; default `1h`. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Set to `FRAMEWORK` only behind a trusted proxy that strips client-supplied forwarded headers; default `NONE`. |

The worker lease defaults to two minutes. Three sequential Keycloak connect plus read timeout windows must fit strictly inside the lease or startup fails. Non-loopback Keycloak, issuer, and redirect URLs must use HTTPS. Backoff starts at five seconds, applies jitter, caps at one hour, and honors `Retry-After` within that cap. Retryable work has no automatic terminal attempt cutoff.

Source throttling uses the servlet remote address. When deployed behind a proxy, configure forwarded-header handling only after the trusted proxy is configured to remove untrusted incoming `Forwarded` and `X-Forwarded-*` headers. Leaving the default `NONE` behind a shared proxy intentionally treats that proxy as one coarse source and can permit global source-bucket exhaustion.

## Monitoring

Monitor oldest due queue age, due operation count, expired leases, reconciliation count, retry-alert rate, completion throughput, and the counts of `PENDING_VERIFICATION` and `ACTIVE` Customers. Do not use email, raw idempotency keys, external subjects, credentials, tokens, or response bodies as metric labels.

During the verified-activation migration, record these counts before and after Flyway. After migration, the `ACTIVE` count must be zero until verified logins restore access; every migrated Customer must be reauthenticated through OIDC with `email_verified=true`.

```sql
SELECT status, count(*) AS customers
FROM customers
GROUP BY status
ORDER BY status;
```

```sql
SELECT count(*) AS due_operations,
       now() - min(next_attempt_at) AS oldest_due_age
FROM registration_operations
WHERE state IN ('PENDING_IDENTITY', 'IDENTITY_LINKED')
  AND next_attempt_at <= now();
```

```sql
SELECT count(*) AS expired_leases
FROM registration_operations
WHERE state IN ('PENDING_IDENTITY', 'IDENTITY_LINKED')
  AND lease_until <= now();
```

```sql
SELECT failure_code, count(*)
FROM registration_operations
WHERE state = 'RECONCILIATION_REQUIRED'
GROUP BY failure_code;
```

Alert when queue age exceeds the expected email-delivery objective, expired leases grow continuously, reconciliation count increases, or `paycore.registration.retry.threshold` rises. Tune poll delay and batch size before shortening leases. Increase leases whenever remote timeouts increase; do not use a lease shorter than the validated worst-case remote sequence.

## Idempotency Retention And Rotation

- Completed and duplicate-suppressed results remain queryable for at least 24 hours from acceptance.
- Reconciliation-required operations are never removed automatically.
- Cleanup may remove only expired `COMPLETED` and `DUPLICATE_SUPPRESSED` operations.
- Scheduled cleanup also removes expired distributed rate-limit buckets; alert on cleanup failures to prevent unbounded table growth.
- During digest-key rotation, add the new version, make it current, and retain every previous key for at least 24 hours after its last use.
- Remove an old key only after no unexpired operation can reference a digest produced with it.
- Never expose or store the raw idempotency key.

## Reconciliation

1. Locate operations by internal operation or Customer ID, never by exposing registration state publicly.
2. Inspect the sanitized `failure_code` and Keycloak audit events without copying tokens, credentials, email bodies, or full external responses into tickets or logs.
3. For ambiguous creation, link only one exact canonical-username user whose `paycore_customer_id` equals the Customer ID.
4. Never link, modify, or delete an unrelated same-email Keycloak user automatically.
5. Resolve issuer/subject conflicts by establishing ownership before any operator-controlled correction.
6. Keep the Customer non-active until the identity link is durable and Keycloak has accepted the required-action email.
7. Use a documented operator procedure to move an unrecoverable Customer to `PROVISIONING_FAILED`; automatic retries must not do so.

## Rollout

1. Stop every old PayCore application replica and registration worker. Do not use a rolling deployment: old replicas can write `ACTIVE` and cannot deserialize `PENDING_VERIFICATION`.
2. Disable registration and authentication, drain callbacks and protected traffic, and take an encrypted PostgreSQL backup.
3. Record Customer status counts and active-session counts without selecting session attributes:

   ```sql
   SELECT status, count(*) AS customers FROM customers GROUP BY status ORDER BY status;
   SELECT count(*) AS active_sessions FROM spring_session
   WHERE expiry_time >= (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint;
   ```

4. Apply Flyway V4 with the new application while registration and the worker remain disabled. Verify migrated Customers are `PENDING_VERIFICATION`, migrated sessions are absent, and non-active statuses are unchanged.
5. Import and validate the Keycloak realm, exact redirect, user-profile ownership attribute, SMTP, and service-account role.
6. Run PostgreSQL integration/concurrency and Keycloak 26.5.2 contract tests. Confirm no old process remains before admitting traffic.
7. Enable the worker while the public endpoint remains disabled. Monitor queue age, retries, identity links, required-action delivery, and verification/activation metrics.
8. Require forced OIDC reauthentication for migrated Customers; only `email_verified=true` may restore `ACTIVE` access.
9. Enable the public endpoint and monitor generic `202`/`429` behavior, queue health, Customer status counts, and authentication denials.

## Rollback

1. Disable `PAYCORE_REGISTRATION_ENABLED` to stop accepting new work.
2. Disable `PAYCORE_REGISTRATION_WORKER_ENABLED` to stop remote side effects.
3. If V4 has run, keep the new application compatible with `PENDING_VERIFICATION`; the previous application is not a valid rollback target.
4. Do not revert or edit released Flyway migrations and do not bulk-restore Customers to `ACTIVE` or delete identity links.
5. Use a forward fix or restore the matching pre-migration database backup with the matching application version; reauthentication is the only normal path back to `ACTIVE`.
6. Allow active remote calls to finish within their bounded timeout and leases to expire.
7. Reconcile claimed, identity-linked, and reconciliation-required operations before re-enabling.
8. Restore the previous application and realm configuration only before V4 runs and only if it remains compatible with persisted issuer/subject links and digest versions.
