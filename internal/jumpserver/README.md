# JumpServer integration contract

The HTTP service accepts a Core connection token. `Open` exchanges it through
Wisp and returns a server-only connection configuration plus a Core audit session.
It never accepts a database address, password or DSN from the browser.

The caller must:

1. Start `WispProvider.Run` for outbox delivery, admin control and time limits.
2. Call `Open`, open/ping the database, then call `Connected`. On any failure call
   `Close`. `Connected` records success only once and only after a real connection,
   and transfers database-resource cleanup ownership to the caller even before
   its Watch callback has registered.
3. Keep `Watch` attached to the logical session and cancel/close all database work
   on `terminate`. The registered resource owner must stop/join database
   cancellation before calling `Close`; this ordering keeps a gateway forward
   alive long enough to send a server-side KILL. Watch returning does not transfer
   that responsibility back to the provider. Stop `Run` only after the owner has
   drained; Run shutdown supplies a final close fallback. Browser disconnection
   does not end a database transaction.
4. Call `Grant.Check` before operations and result delivery, `Authorize` before
   every statement, and `SetBusy` around database work. `Touch` must represent user
   activity; polling and event subscriptions must not reset an idle timeout.
5. Audit executed and denied statements with `Record`. Preserve SQL outcome when
   remote audit delivery fails. A local persistence error is `ErrAuditDisk` and
   must be exposed as an audit failure separately from execution outcome.
6. Mask values before returning/caching results. Pass an empty source column if
   the driver only supplies a result label. For sessions with field masking rules,
   unknown source columns are conservatively masked to prevent alias bypass.

## Policy compatibility

- Core-provided ACL/group order is preserved. First match wins.
- Reject denies SQL before database execution. Warning executes with warning
  audit risk. NotifyWarning requires explicit confirmation of immutable SQL via
  `confirm_warnings`; this never bypasses rejection or review. Wisp notifies Core
  only for SQL actually sent to the database.
- Review requires an explicit submission through `CommandAuthorizer`. Check the
  entire script and every statement for rejection before submitting any ticket.
  Bind tickets to the exact immutable batch, deduplicate matching reviews only
  within that execution, and never cache approvals on a connection. Core stores
  at most 4090 Unicode characters: longer reviewed batches are rejected, never
  truncated. A pending ticket blocks SQL until approved; rejection, cancellation,
  timeout and unknown/error responses fail closed. Waiting lasts at most 30
  minutes and rechecks session permission before execution. HTTP/SSE expose the
  ticket link, reviewers and state, not Wisp's privileged check/cancel requests.
- Approval wait uses a separate bounded deadline. The engine suspends the SQL
  budget during review and resumes its remaining budget afterward. Queueing and
  database execution remain bounded. Mixed scripts retain their batch audit plus
  warning records for distinct warning ACL/groups on SQL actually sent to the
  database, so an approved command cannot suppress a warning notification.
- Invalid or Java-only regex syntax is rejected during token authentication.
  RE2 is a bounded subset, not a claim of complete Java regex compatibility.
- A face-monitor token registers through Wisp before a database connection is
  allowed; failure compensates the Core session and fails closed. Non-password
  database credentials explicitly require legacy Chen. Unknown admin actions lock
  the session.
- Idle limits are minutes; maximum duration is hours. Running work suspends the
  idle timer, but does not suspend permission expiry or maximum duration.
- Core dynamic permission expiry retains the established ten-minute grace. A
  repeated event cannot extend it; a permission-valid event clears it.
- Losing Chen's Wisp task stream pauses existing sessions and rejects new token
  exchanges until the stream reconnects. Recovery preserves independent admin
  locks. Wisp v0.2.15 does not expose the health of its upstream Core WebSocket in
  this protobuf contract, so a live Chen/Wisp stream does not prove Core control
  connectivity. Wisp checks token validity every five minutes; this is not an
  instantaneous authorization revocation guarantee.

## Durable audit and cleanup

The spool directory is required, checked at startup and restricted to mode 0700.
Entries contain audit SQL and bounded status summaries, use 0600, and are fsynced
before `Record` acknowledges acceptance. Do not put the spool on ephemeral storage
when audit durability across a container replacement is required. Raw result rows,
connect tokens, database passwords and TLS private keys are not stored there.

Run retries command audit, lifecycle logs, replay upload, session finish, ticket cancellation and
forward cleanup after remote failure or restart. Successful cleanup steps are
checkpointed. UploadCommand has no idempotency key in Wisp v0.2.15, so lost replies may produce duplicate audit
records. The outbox never re-executes SQL.

Each session records asciinema v2 SQL input and the same redacted bounded status
summary as command audit; raw query result rows are excluded. Replay upload is
acknowledged before local files are deleted and before session finish. The durable
canonical cast regenerates a compressed file after a lost Wisp response; startup
recovers unfinished local casts without executing SQL.

Canceling a pending review durably queues Core ticket closure. A bounded detached
observer also closes a ticket returned after the requesting execution has been
canceled. Wisp's CreateCommandTicket has no idempotency key: an entirely lost
creation response leaves no ticket ID to compensate, so creation is not retried.
Known cancel requests remain in the outbox across restart until delivered.

Core sessions use a generated UUID supplied to CreateSession. This enables cleanup
after an unknown CreateSession RPC outcome. Browser cancellation during a forward
creation does not abandon the RPC result: a bounded detached observer queues late
returned IDs for deletion. Wisp's CreateForward has no request idempotency key;
when its remote response is lost entirely, an unreturned forward ID cannot be
recovered by this client. A future Wisp idempotency change is needed to remove
that protocol limitation completely.

## Verification boundary

Unit/contract tests exercise a generated gRPC client/server round trip using
in-memory transport, including approval states, exact SQL binding, late ticket
cleanup, control-stream loss/recovery and independent admin locks, plus injected
auth, audit, disk, resource-cleanup and cancellation failures.

`internal/engine/managed_live_integration_test.go` is separately opt-in through
`CHEN_MANAGED_FIXTURE`. It requires a private fixture for an isolated loopback
Core/Wisp with PostgreSQL, dedicated ordinary operator/reviewer accounts, the
`chen_rejected`, `chen_review`, `chen_warning` policies and a local notification
sink and a running Celery worker. It creates fresh sessions/tickets and only
sends synthetic SELECT SQL. Temporary masking/ACL rules are scoped to the fixture
operator and asset and deleted afterward; permission changes are restored.
It checks Core's persisted audit and session state separately from Chen's local
`recorded` acknowledgement; Wisp asynchronously batches uploads. It does not
exercise production, external mail delivery or gateways.

On 2026-10-26 the isolated Core + Wisp v0.2.15 + PostgreSQL run passed against the latest local dev checkout. Its acceptance coverage is:

| Interaction | Evidence boundary |
| --- | --- |
| Token login and permissions | Real Core token exchange, PostgreSQL connection, copy/paste/transfer flags, denial of new tokens after permission removal |
| SQL policy and approval | Real reject, explicit submit, exact batch inspection, approve/reject/cancel/timeout, admin lock during review, canceled Core ticket |
| Warning and auditing | Explicit NotifyWarning confirmation and persisted Core command records and reviewer site messages; a mixed approved batch still generates its warning; parse failure and an unsent warning statement do not |
| Admin lock/unlock/kill | Real Core task delivery, locked query refusal, query after unlock, revoked Chen session token and Core session completion after kill |
| Replay | Core marks sessions as having replay, stores nonempty gzip files, and the files reopen as valid asciinema v2 |
| Component heartbeat | Core reports a newly changed heartbeat Status UUID, alive component and online sessions while the test connection stays open |
| Data masking | Real Core rule distribution and masked synthetic text, NULL and unknown-origin alias results before HTTP delivery |
| Dynamic permission change | `CHEN_MANAGED_REVOCATION=1` separately waits up to six minutes for Wisp's real five-minute check and observes the revocation control; the ten-minute expiry grace is retained |

Live gateway forwarding/TLS, face monitoring, non-password credentials, external
mail delivery, Core outage recovery and the full ten-minute revocation deadline
were not deployment-tested in this run. Control-stream interruption/recovery,
cleanup retries and expiry timing have contract-test coverage. Clipboard flags
describe the client interaction policy; they are not a guarantee against copying
data already delivered to an authorized browser.
