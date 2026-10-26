# Chen local IBM CLI driver patch

Upstream: https://github.com/ibmdb/go_ibm_db, v0.5.4, commit
`2a78b7f857aaa6684ff2ef5bc7f42d6da53212d1`. Upstream LICENSE.md is retained. This is a source-only copy; upstream test fixtures, installers and generation scripts are omitted.

The real Db2 matrix on macOS ARM64 and Go 1.26.3 exposed a CGO pointer panic
when a fixed-width column followed a LOB. This local patch keeps CGO checks
fully enabled and:

- uses separate scalar/output allocations for SQLGetData; avoids pointers
  into a pointer-bearing Go struct;
- fetches columns sequentially, avoiding retained SQLBindCol Go pointers;
- pins parameter data/indicators until rebinding or native statement release;
- streams LOBs in bounded 64 KiB chunks and rejects values over 16 MiB;
- fetches NUMERIC as text, preserving decimal precision;
- converts DBCLOB through CLI SQL_C_WCHAR, preserving Unicode and empty values;
- removes automatic application flag parsing and connection credential tracing.

It does not add context cancellation. Chen exposes can_cancel=false for Db2
and only allows queued work to be withdrawn locally. IBM CLI remains a native
runtime prerequisite and is not included in this source directory.

CLI QueryTimeout=1 was verified against the authorized real Db2 fixture: a
synthetic recursive CTE returned SQLSTATE 57014 in 1.318 seconds and the same
connection remained usable. Chen sets QueryTimeout=60 as a native execution
limit; this does not make an individual Go context cancel the running query.

## Building Chen with Db2

Install the IBM Data Server Driver for ODBC and CLI separately. Its license and
native binaries remain an external prerequisite. From the Chen repository:

```sh
export CGO_CFLAGS="-I$PWD/data/drivers/ibmdb/clidriver/include"
export CGO_LDFLAGS="-L$PWD/data/drivers/ibmdb/clidriver/lib -Wl,-rpath,$PWD/data/drivers/ibmdb/clidriver/lib"
go build -tags ibmdb -o build/chen-go ./cmd/chen-go
```

The local macOS ARM64 CLI distribution required an install-name correction
before linking, followed by ad-hoc signing of that local library:

```sh
install_name_tool -id '@rpath/libdb2.dylib' data/drivers/ibmdb/clidriver/lib/libdb2.dylib
codesign --force --sign - data/drivers/ibmdb/clidriver/lib/libdb2.dylib
```

Keep the configured runtime library directory present. Other operating systems
must use the matching IBM CLI distribution and platform loader configuration.
Default builds omit Db2 and do not advertise it in the API's capabilities.
