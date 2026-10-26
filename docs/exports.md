# Read-only result exports

The workspace offers **cached result** export and **full query** export as separate choices. Cached export serializes the retained, already masked result and carries its `truncated` flag. It never reruns SQL. Full export requires explicit confirmation of a frozen SQL snapshot and reruns exactly one read query on the original console, preserving its database/schema, temporary tables and serial execution order. It is a new database read, so data may have changed since the visible result was produced.

Managed sessions require Core's `can_download` permission at creation, during generation, when issuing a download ticket, and while streaming the file. Full export uses the same command ACL, warning confirmation, review ticket, masking, cancellation and audit path as interactive execution. Confirmation and review flags are bound to the immutable SQL and stable request ID. A failed confirmation request holds no artifact quota. Single mode enables download for its locally configured account; upload remains disabled.

CSV and XLSX are written one row at a time to private temporary files. XLSX uses standard SpreadsheetML ZIP parts and inline text cells, without a shared-string table or an in-memory workbook. Numeric strings keep their precision; formula-looking values are text. The browser submits a native download form and Electron streams the response to its selected local file. Neither path loads the complete artifact into a JavaScript Blob.

## Limits and representation

- At most 1,000,000 data rows, 128 MiB of JSON-encoded raw records, and 128 MiB of generated file per job. Every limit fails explicitly and removes the partial file.
- XLSX allows at most 16,384 columns and 32,767 UTF-16 units in one cell. A value exceeding the cell limit fails with `export_cell_limit`; CSV preserves larger values within the job and 16 MiB per-value materialization budgets. Files are never silently truncated or split into misleading partial workbooks.
- Full export has a 10-minute execution/queue budget; waiting for command review pauses that budget. Cached export has a 2-minute generation budget. Driver cancellation capability remains authoritative: DM and Db2 do not advertise active query cancellation.
- Four artifacts or running jobs per server, retained for 15 minutes after generation. Failed jobs immediately release capacity. Deleting a completed export frees capacity early. A session close removes its artifacts.
- CSV prefixes formula-looking strings with an apostrophe. SQL NULL is `\N`; literal strings beginning with a backslash receive an extra leading backslash. Empty strings remain empty. XLSX represents SQL NULL as an absent cell and empty strings as explicit inline string cells. Precise numbers are text cells rather than rounded spreadsheet numbers.

The root export directory and per-instance directories are mode `0700`; files and lock files are `0600`. Each server holds an OS file lock on its private instance directory. Startup removes abandoned instance directories after acquiring their lock, while preserving other live instances. Normal shutdown removes the instance directory. Older flat temporary exports are removed once older than 30 minutes. Unsupported process-lock platforms fail export storage initialization rather than sharing unprotected temporary files.

## HTTP contract

- `POST /chen/api/v1/results/{rid}/exports`: `{request_id, format: "csv" | "xlsx"}` creates a cached export.
- `POST /chen/api/v1/consoles/{cid}/exports`: `{sql, request_id, document_version, format, confirm_warnings?, submit_review?}` returns an execution with `export_id`.
- `GET /chen/api/v1/exports/{id}` and the existing session SSE `export.updated` expose status, rows, raw bytes, limits, expiry and readiness. Progress is emitted at most every 250 ms; completion is immediate. Only a finished file with successful audit is ready for download.
- `POST /chen/api/v1/exports/{id}/cancel` uses the existing execution cancellation contract; cached writing can always be canceled. `DELETE /chen/api/v1/exports/{id}` removes a finished artifact.
- `POST /chen/api/v1/exports/{id}/download-ticket` returns a one-use, 60-second ticket, a relative download endpoint and native form fields. Submit `application/x-www-form-urlencoded` to `POST /chen/api/v1/downloads`. The ticket is sent in the request body, never in the URL or referrer. No SQL runs when downloading. Choose an Electron save path before issuing the ticket.

No background export creates business tables or uses database-side filesystem export commands.

## Verification

`go test -race ./internal/engine -run 'Test(FullExport|Export|CachedExport)'` covers generated 12,000-row / greater-than-16-MiB datasets, streaming CSV/XLSX decoding, exact numeric strings, Unicode, formula protection, NULL/empty strings, limits, policy gates, cancellation, one-use downloads, a session-close/audit race, and crash cleanup with another live instance present. Independent openpyxl decoding also verifies the synthetic XLSX artifact.

With the private read-only backend fixture, `CHEN_READONLY_BACKENDS=/absolute/private/backends.yml go test ./internal/engine -run TestReadonlyPostgresExportJobs -v -count=1` exercises actual PostgreSQL constant queries, native HTTP file downloads, production row/byte thresholds and original-console recovery. It does not read application rows or write database objects.

Spreadsheet limits and inline-string structure follow [Microsoft's Excel limits](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits) and [SpreadsheetML cell documentation](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.cell).
