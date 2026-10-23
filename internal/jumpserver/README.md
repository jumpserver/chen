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
- Reject, Review and NotifyWarning are enforced. Review and NotifyWarning require
  legacy Chen until their approval/notification workflows are implemented here;
  they are never silently accepted. Warning executes with warning audit risk.
- Invalid or Java-only regex syntax is rejected during token authentication.
  RE2 is a bounded subset, not a claim of complete Java regex compatibility.
- Face monitoring and non-password database credentials explicitly require legacy
  Chen. Unknown admin actions lock the session.
- Idle limits are minutes; maximum duration is hours. Running work suspends the
  idle timer, but does not suspend permission expiry or maximum duration.
- Core dynamic permission expiry retains the established ten-minute grace. A
  repeated event cannot extend it; a permission-valid event clears it.

## Durable audit and cleanup

The spool directory is required, checked at startup and restricted to mode 0700.
Entries contain audit SQL and bounded status summaries, use 0600, and are fsynced
before `Record` acknowledges acceptance. Do not put the spool on ephemeral storage
when audit durability across a container replacement is required. Raw result rows,
connect tokens, database passwords and TLS private keys are not stored there.

Run retries command audit, lifecycle logs, session finish and forward cleanup after
remote failure or restart. Successful cleanup steps are checkpointed. UploadCommand
has no idempotency key in Wisp v0.2.12, so lost replies may produce duplicate audit
records. The outbox never re-executes SQL.

Core sessions use a generated UUID supplied to CreateSession. This enables cleanup
after an unknown CreateSession RPC outcome. Browser cancellation during a forward
creation does not abandon the RPC result: a bounded detached observer queues late
returned IDs for deletion. Wisp's CreateForward has no request idempotency key;
when its remote response is lost entirely, an unreturned forward ID cannot be
recovered by this client. A future Wisp idempotency change is needed to remove
that protocol limitation completely.

## Verification boundary

Tests exercise a generated gRPC client/server round trip using in-memory transport,
including admin controls and acknowledgements, plus injected auth, audit, disk,
resource-cleanup and cancellation failures. They do not claim compatibility with a
deployed Core/Wisp, live gateway or real database; those need deployment acceptance.
