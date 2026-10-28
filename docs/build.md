# Go build and JumpServer integration

## Build chain

The primary `Dockerfile-base` contains Go 1.26.3, its target-architecture C
compiler, and Wisp v0.2.15. `Dockerfile` compiles Chen with CGO and copies only the
runtime executable, Wisp and optional IBM CLI into Debian bookworm-slim.
`Dockerfile-ee` retains the existing thin CE extension (ping/telnet). Java, Maven,
Node.js and the source tree do not enter the final runtime image.

BuildKit builds CGO on the target architecture. Use native amd64/arm64 builders
for release or QEMU for local acceptance. Wisp alone is pure Go and can be
cross-built on the build platform. Do not use `CGO_ENABLED=0` for Chen, or copy its
glibc-linked binary into Alpine.

```sh
make image-ee VERSION=dev PLATFORM=linux/arm64
make image-ee VERSION=dev PLATFORM=linux/amd64
```

The default base is pinned consistently in `Dockerfile`, `Makefile` and
`docker-compose.yml`. `make image` builds it locally before using it. Set
`CHEN_BASE_IMAGE` to use another exact reference or digest.

Like Magnus, the Build and Push Base Image workflow runs on base-related changes
in PRs targeting `dev` or `v*`, or manually with an existing source branch. One job
publishes `jumpserver/chen-base:<UTC YYYYMMDD_HHMMSS>` for amd64/arm64, then
`<timestamp>-db2` for amd64. An existing tag is rejected. Only after both builds
and pushes succeed does it commit the three default references back to the source
PR/branch. The workflow recognizes that exact generated three-file delta and
skips its own rebuild (PR path filters include the whole PR diff). Application CI
still validates the updated defaults.

Manual builds of `dev`, version branches or other protected branches create a
`pr@<source>@build_chen_base_<timestamp>` branch and PR instead of pushing directly
to the protected branch. A source branch that advances during the build is not
overwritten; rebuild its latest revision. Fork PRs only build both variants, with
no registry login, publication or writeback. Like Magnus, publication requires
`PRIVATE_TOKEN` with repository push/PR permissions so the existing generic PR
handler and subsequent CI can run. A missing token fails before either image is
published. There is no `GITHUB_TOKEN` fallback: bot-token pushes would not trigger
the application checks on the updated commit.

The writeback helper can preview its exact three-file diff without mutation:

```sh
GITHUB_REPOSITORY=jumpserver/chen python3 scripts/update-base-image.py 20261026_120000 \
  --source-branch dev --source-sha "$(git rev-parse HEAD)" --dry-run
```

The helper requires a clean checkout at the built SHA and uses read-only GitHub
branch metadata even in dry-run mode. It never stages unrelated files or forces
a branch update.

Source builds and release packaging use the primary `Dockerfile` and
`docker-compose.yml` entry points.

## Db2 variant

```sh
make image-ee VERSION=dev WITH_DB2=1 PLATFORM=linux/amd64
```

This produces `jumpserver/chen-base:<BASE_VERSION>-db2` and
`jumpserver/chen:dev-db2-ce` / `dev-db2-ee`. Both base and application stages must
receive `WITH_DB2=1`. A mismatched base fails while checking the CLI headers.
The runtime includes the entire `clidriver` directory under
`/opt/chen-deps/clidriver`, not only `libdb2.so`; metadata, messages and supporting
libraries remain available. No customer Db2 Connect license is included.
The runtime and Linux archive wrapper use `C.UTF-8`. IBM CLI derives its client
code page from the locale; a plain `C` locale replaced Chinese and emoji values
with substitution characters during the actual Linux image test. Keep the
client locale UTF-8 when invoking the binary directly.

IBM CLI 12.1.2 is downloaded only in the base build and SHA-256 checked.
`DB2_CLI_URL` may point to a reachable mirror of that exact archive; the checksum
is still enforced. `APT_MIRROR` configures Debian package downloads while their
signatures remain verified. The
observed [official 12.1.2 distribution directory](https://public.dhe.ibm.com/ibmdl/export/pub/software/data/db2/drivers/odbc_cli/v12.1.2/)
contains Linux x64, ppc64le and s390x64 packages, but no Linux arm64 package.
The standard arm64 image therefore omits Db2 and reports this in capabilities.
A successful macOS ARM64 Db2 test does not establish Linux ARM64 compatibility.
See [IBM's native-driver installation instructions](https://github.com/ibmdb/go_ibm_db/blob/master/INSTALL.md)
for platform libraries and Db2 Connect licensing when connecting to z/OS/iSeries.

Db2 cancellation and TLS retain the explicit limitations in
[database-compatibility.md](database-compatibility.md). Including the driver in an
image does not remove those protocol limitations.

## Runtime and existing installations

The runtime uses UID/GID 10001 and port 8082. Docker creates new named volumes
with the image directory ownership. For an existing bind-mounted data directory,
arrange write access for UID 10001 before switching images; keep `data/keys` and
`data/go` private and persistent. Do not discard registration or queued audits
as part of migration. The Go audit state is separate from legacy Java state.

The default entrypoint supervises Wisp and Chen as siblings. It sends TERM to
Chen, waits up to 40 seconds for HTTP/session cleanup and audit drain, then stops
Wisp. Compose allows 55 seconds. If either process exits unexpectedly, the other
is stopped and the container fails. Wisp v0.2.15's own subprocess supervisor uses
SIGKILL; it is deliberately not used to own the Go process.

The Wisp build applies the reviewed Chen patch to a private copy of v0.2.15 and
records `v0.2.15+chen.1` in `wisp.version`. It removes the startup Config print,
which includes BootstrapToken, and acknowledges command uploads only after the
configured audit storage has saved them. An upload failure leaves Chen's durable
outbox available for retry; stopping Wisp must not discard an acknowledged
in-memory command queue. Delivery remains at least once: if persistence succeeds
but its acknowledgement is lost, a retry can produce a duplicate audit record.
The original module cache and local Wisp source checkout remain unchanged.
The initial `-chen1` base tag and subsequent timestamp releases contain this
patch; `wisp.version` records the contract independently of the image tag.

`chen-go --version` prints the build version, OS/architecture and Wisp module
version. `chen-go healthcheck` checks its own `/healthy` listener without ambient
proxies or redirects. This is liveness, not proof of Core registration or an
open authorized database session. Managed acceptance must establish those
separately. HTTP and query logs must not contain connection credentials.

## Separate Wisp during development

```sh
make image
make run COMPOSE_FILES='-f docker-compose.yml -f docker-compose.wisp-dev.yml'
```

The override uses the same pinned image and exposes Wisp only inside the Compose
network. Remote plaintext gRPC is explicitly selected only on that private
network. For other deployments use `--wisp-ca` and `--wisp-server-name`.
Both services mount the same data volume at `/opt/chen/data`. Wisp reads replay
paths supplied by Go, so the replay directory must have the same absolute path
and file visibility in both processes. The default is
`/opt/chen/data/go/outbox/replays`. Separate containers must not mount this volume
at different locations.

For Wisp source development, additionally apply
`docker-compose.wisp-source.yml` and set `WISP_SOURCE` to the source checkout.
Native `./start.sh managed` also accepts `WISP_SOURCE`; absent that setting it
builds the pinned module. There is no dependency on a sibling Wisp checkout or a
branch-derived registry tag. A Wisp configuration file must leave
`EXECUTE_PROGRAM` empty when using sibling-process mode.

## Full JumpServer wiring

1. Start matching Core, PostgreSQL, Redis and Core workers. Configure terminal
   registration and the bootstrap credential privately. The Chen Compose project
   does not create or reconfigure an existing Core installation.
2. Start managed Chen/Wisp; verify Core terminal registration and a live task
   control stream. Retain the data volume across restarts. Core's `auto`
   registration policy has a startup window; start a newly registered Wisp
   within that window, and resolve registration-name conflicts in the intended
   Core environment. Reuse the valid persisted component key on later starts.
   A listening Chen health endpoint alone does not prove registration succeeded;
   do not permanently open component registration to work around a failed start.
3. Build the corresponding Luna checkout, then include its generated `/opt/luna`
   output in docker-web with Lina. docker-web's default Bake contexts point to
   remote `dev`; override them with the intended local/source revision when
   accepting unpublished changes. A new Chen image alone does not update Luna.
4. Route `/chen/api/v1/` to `chen:8082` as normal HTTP. Keep SSE response buffering
   off and allow its long-lived read stream. Do not force WebSocket upgrade for
   these requests. Core's separate WebSocket endpoints remain unchanged.
5. Use one public origin for Core, Luna and Chen; authenticate through the real
   asset launch flow. Never retry a consumed connection token against a second
   backend generation. Exercise the web and Electron connector selection.

## Repeatable acceptance

```sh
make test vet
./scripts/test-entrypoint.sh
make dev-config
make dev-config COMPOSE_FILES='-f docker-compose.yml -f docker-compose.wisp-dev.yml'
make image-ee VERSION=ci PLATFORM=linux/arm64
./scripts/smoke-image.sh jumpserver/chen:ci-ce linux/arm64 0
./scripts/smoke-image.sh jumpserver/chen:ci-ee linux/arm64 0
```

The smoke script creates only its own network and disposable PostgreSQL/container
volumes, copies a private configuration with UID 10001, and removes those
resources on exit. It tests the actual non-root image: selector authentication,
SELECT, table/alias/JOIN completion, Unicode large values and exact bigint,
NULL/empty distinction, cancellation, local audit state and graceful TERM. It
also exports 10,001 synthetic rows as CSV and XLSX, validates exact Unicode and
integer values, verifies formula handling and NULL/empty distinction, and checks
that native POST download tickets are single use. The export files are written
by UID 10001 under the same persistent state path as the deployed service.
The retired synchronous CSV/JSON export URLs must return 404, so they cannot
bypass export-job auditing and permission checks.
Repeat for amd64 and pass `1` for the Db2 variant; capability presence plus `ldd`
checks verify packaging, while a real Db2 connection is a separate gate.

The opt-in real database matrix uses an explicitly supplied private backend file:

```sh
CHEN_READONLY_BACKENDS=/absolute/private/backends.yml \
  go test ./internal/engine -run TestReadonlyDatabaseMatrix -count=1 -v
# For Db2, use the platform CLI and -tags ibmdb as documented in its driver patch.
```

Managed acceptance is independently reproducible against an isolated Core/Wisp
fixture, including approval, control, permissions and audit:

```sh
CHEN_MANAGED_FIXTURE=/absolute/private/fixture.json \
  go test -race ./internal/engine -run TestManagedLive -count=1 -v
```

Fixtures are not committed. A local/mock/HTTP pass is not evidence of a gateway,
production database, or packaged image pass. Record versions and architectures
for each actual run. The CI workflow builds and smoke-tests CE/EE on native amd64
and arm64 plus the amd64 Db2 variant. Tagged native releases include separate
standard amd64/arm64 and amd64 Db2 archives. Live external database and Core checks need
explicit private fixtures and are not silently substituted with mocks.

## Native releases

`make package CHEN_IMAGE=... VERSION=... PLATFORM=linux/amd64` exports the exact
built image into `build/chen-<version>-linux-<arch>.tar.gz` with SHA-256. The archive
uses an additional `-db2` version suffix when `WITH_DB2=1`, so it does not overwrite
the standard architecture archive. The archive
contains its binary, Wisp, native dependencies, license, configuration example,
and a relocatable start wrapper. It requires a glibc environment compatible with
Debian bookworm, plus the documented platform libraries. Run `./start.sh single
--backends ... --web-dir ...` or `./start.sh managed` after unpacking. Luna static
assets are built and supplied separately.
