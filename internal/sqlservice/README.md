# SQL language service

The execution parser and editor completion have separate responsibilities.

* `AnalyzeWithOptions` parses the complete script with the pinned TiDB parser for
  MySQL, or PostgreSQL 17's grammar through `pg_query_go/v6`. It returns original
  UTF-8 byte spans, result-set hints and advisory write classification. Statements
  are never reserialized before execution. PostgreSQL modifying CTEs are inspected
  recursively. These classifications do **not** prove absence of side effects
  (functions, triggers, stored procedures and extensions may perform writes).
* Oracle, SQL Server, Db2, Dameng, ClickHouse and OceanBase Oracle use a
  bounded command scanner for common queries, CRUD, TABLE/INDEX/VIEW DDL and
  transaction commands. It checks quote/comment termination, parentheses,
  statement boundaries and unsupported procedural/batch
  keywords; the **database server validates vendor grammar**. This is not a full
  ANTLR/native AST parser or a guarantee against function side effects. MariaDB
  and OceanBase MySQL use the MySQL parser first with this conservative fallback for
  dialect extensions. Procedural bodies, permission-management commands and
  ambiguous implicit batches are rejected by the fallback. Read-only export gates
  retain a separate stricter SELECT classifier. Original SQL is never rewritten.
* `Completion` accepts a Monaco UTF-16 cursor position and document version. It
  returns a full-token replacement range and insertion text with dialect-correct
  identifier quoting. An error-tolerant lexer and SELECT scope grammar keep
  comments, quoted strings, aliases, nested queries, CTEs and derived projections
  separate. This path does not execute or prepare the user's SQL.
* `Metadata` and `Columns` issue only parameterized catalog queries on the supplied
  authenticated connection. PostgreSQL unqualified names resolve against that
  connection's current `search_path`. Catalog operations have a three-second
  deadline. Completion returns at most 200 candidates, rejects nesting beyond 128
  levels, and accepts documents up to 2 MiB.

The caller must serialize access to each `sql.Conn`. Do not run completion on a
connection already occupied by a query. An independent catalog connection must
carry the same database/schema/search-path context and account permissions. No
global or cross-session catalog cache exists here; only a single completion
request memoizes repeated column lookups. The HTTP/session layer supplies the live
MySQL SQL mode and numeric version through `AnalyzeOptions` and
`CompletionRequest.Options`; this field cannot be set by JSON input. Refresh it
after session settings change.

## Explicit V1 limits

The TiDB grammar is not identical to MySQL. The pinned version accepts stored
procedure bodies but rejects the `DELIMITER` client directive, MariaDB's
`INSERT ... RETURNING` (handled by the bounded fallback), and MySQL's `SELECT ... INTO @variable`. Unsupported
execution syntax returns an error; it is never silently split or rewritten.
MySQL versioned executable comments are masked **only in parser input** according
to the supplied server version, preserving original source offsets and SQL.

PostgreSQL parsing uses a version-17 grammar; parsing a feature does not establish
server-14/16 compatibility. PL/pgSQL function bodies are additionally syntax
checked, but server objects, types, privileges, extension languages and runtime
semantics still require server validation. A parser verdict is not a permission
check.

Completion currently covers common SELECT/JOIN contexts, table/schema prefixes,
columns, alias shadowing, correlated subqueries, CTE explicit columns and projected
columns, derived tables, wildcard projection, common read-query keywords and basic
built-in function signatures. FROM/JOIN/comma relation positions query only table
candidates; expression positions query only referenced relation columns. Ambiguous
columns insert a qualified alias, JOIN ON limits visibility to preceding relations,
and USING offers only shared columns not already listed. ORDER/GROUP BY also offer
explicit projection aliases. Unknown alias-dot expressions return no invented
schema-table candidates. UNION/INTERSECT/EXCEPT branch aliases are isolated.
Unqualified PostgreSQL tables honor live search_path visibility, including same-name
relations in multiple schemas. Candidate replacement ranges cover the complete
identifier token; optional filter_text/sort_text preserve raw-name matching and
server ranking in Monaco. Completion never infers JOIN equality without foreign-key
metadata; foreign-key relationship suggestions remain a future extension. It deliberately omits stored-program variables, dynamic SQL,
table-valued function output types, inferred expression types, exhaustive dialect
function overloads and full set-operation result/order-by semantics. Complex unsupported
editor contexts may yield fewer candidates. A document version in the response
lets the client discard stale replies.

Tests include whole-document syntax failures, original source spans, dollar
quotes, nested comments, stored routines, SQL modes and versioned comments;
completion tests exercise CTE/derived projections, shadowing, Unicode/UTF-16,
quoted identifiers and isolation; catalog-driver tests verify parameterization,
bounded limits and cancellation. They are not a substitute for live MySQL and
PostgreSQL compatibility tests.

## Additional identifier and catalog dialects

Oracle, Db2 and Dameng fold unquoted identifiers to uppercase and preserve quoted
case; SQL Server recognizes escaped bracket identifiers; ClickHouse preserves
case. Oracle q/NQ literals (including multibyte delimiters), SQL Server TOP
modifiers and Oracle MINUS branches remain isolated in completion. Catalogs use
the current authenticated connection and each engine's visibility rules. Parameter
markers follow the driver, including OceanBase Oracle's `?` wire markers.

Real-instance tests are opt-in via `TestReadonlyDatabaseMatrix`. They only read
catalog metadata, use `WHERE 1=0` for object identifier validation, and return
synthetic constants for long Unicode text, exact integers/JSON and binary values.
They do not create database objects or read application rows. Mock tests alone do
not establish server-version compatibility; see `docs/database-compatibility.md`.
