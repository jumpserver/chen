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

### Managed startup and readiness

Previously the entrypoint started both processes and Chen immediately opened
HTTP: `grpc.NewClient` only configured a client. `/healthy` could succeed while
Wisp was still validating its access key or registering with Core.

The sibling process layout is retained, but startup now follows:

```text
entrypoint starts Wisp and Chen
  Wisp retries Core registration/key validation, loads terminal configuration
  Chen waits for a successful Wisp GetPublicSetting RPC (including Status.Ok/data)
  Chen initializes its persistent outbox and starts audit/control workers
  Chen opens HTTP; /ready additionally checks local initialization and control state
```

`CHEN_STARTUP_TIMEOUT=120s` (or `--startup-timeout 120s`) bounds Chen's managed
startup wait. Each RPC has a two-second deadline, retries pause one second, and
each failed attempt logs a fixed code and attempt number. No RPC response,
Core URL, token or credential is logged by these probes. Wisp has its own bounded
registration retry policy and can fail before this deadline; increasing Chen's
timeout does not extend Wisp's retry policy. The supervisor propagates an early
Wisp exit, stops a waiting Chen, and treats an unexpected zero exit as failure.
SIGTERM cancels the wait promptly. After startup the existing shutdown order is
unchanged: Chen HTTP shutdown (10s), session cleanup (15s), provider drain (up to
12s), then Wisp; the supervisor's limits remain 40s/10s and Compose allows 55s.
The signal context used for startup does not cancel the running audit provider
before sessions have closed.

No separate Core `/api/health/` gate is needed. That endpoint returns HTTP 200
even when its JSON `status`, `db_status` or `redis_status` is false. A bare HTTP
200 would therefore not be a substitute for initialization.

| Check | Meaning |
| --- | --- |
| `GET /healthy`, `chen-go healthcheck` | HTTP liveness, 200 with `{"status":"ok"}`; no external probe. |
| `GET /ready`, `chen-go readycheck` | 200 with `{"status":"ready"}`, or 503 with `{"status":"not_ready"}`. Local export storage must initialize and shutdown must not have begun. Managed mode also requires the local task worker to be available and a successful read-only Wisp/Core public-settings RPC. |
| `chen-go wisp-healthcheck` | One bounded Wisp public-settings RPC using the same address/TLS flags; opens no journal. Used for the separate Wisp Compose startup dependency. |

Before managed initialization finishes, HTTP is not listening, so both HTTP
checks fail to connect; neither reports healthy/ready. After listening, a lost
dependency changes readiness to 503 without changing liveness, stopping sessions
or initiating a restart. Existing task-stream protection still applies. A
successful probe proves one Core settings read, **not** Core WebSocket health,
all database/Redis dependencies, permission validity or a database session.
Wisp v0.2.15 exposes no authoritative upstream WebSocket health signal. The local
stream flag is not an upstream handshake acknowledgement. No health request
consumes a one-time connection token. Single/standalone mode does not initialize
Wisp or depend on any Core check or managed startup timeout.

Managed providers start fail-closed: `Open` refuses token exchange before the
control worker runs, as well as during a known control-channel outage. There is
no "not yet monitored" bypass. A recovered control stream restores readiness
without clearing independent administrator locks.

Each `/ready` request makes one Wisp RPC and one Core settings GET; `/healthy`
makes neither. Use a modest readiness cadence (for example 15s), not a browser
polling loop. The two-second RPC deadline bounds Chen's response, but pinned
Wisp's SDK call does not inherit that context and may continue up to its own
30s HTTP timeout. No cache or background health state machine is introduced;
high-frequency probes would require a separate measured load/timeout change.

The CE Dockerfile already defines the 15s/5s/60s/3-retry liveness HEALTHCHECK;
EE inherits it from CE. The base is a build image and needs no service check.
These intervals are unchanged. Installer's `chen-go healthcheck` branch remains
compatible; the `/chen/healthy` fallback is only for legacy Java images and must
not be selected for Go images. Keep readiness separate from restart decisions,
including in any external auto-heal tooling. Docker health status alone does
not restart a container; `restart: unless-stopped` acts on process exit.

### Verify the deployed image and effective healthcheck

Run these after updating/recreating Chen with the intended Installer/Compose
configuration. Do not delete the data volume or registration keys. A mutable
tag on the host may refer to a different image from the running container:

```sh
docker image inspect jumpserver/chen:dev-ce \
  --format 'id={{.Id}} digests={{json .RepoDigests}} health={{json .Config.Healthcheck}}'
docker inspect jms_chen \
  --format 'tag={{.Config.Image}} id={{.Image}} health={{json .Config.Healthcheck}} state={{json .State.Health}} restarts={{.RestartCount}}'
image_id=$(docker inspect --format '{{.Image}}' jms_chen)
docker image inspect "$image_id" \
  --format 'id={{.Id}} labels={{json .Config.Labels}} user={{.Config.User}} health={{json .Config.Healthcheck}}'
docker exec jms_chen chen-go --version
docker exec jms_chen cat /usr/local/bin/wisp.version
docker exec jms_chen id
docker exec jms_chen chen-go healthcheck
docker exec jms_chen chen-go readycheck
```

In the deployment directory, use the **same Compose files and environment** as
Installer to inspect `docker compose config` and compare its `chen.healthcheck`
with the container output above. Avoid sharing the full config: environment
entries may include credentials. A container override can provide a working
check even when the selected image has none. If the image itself has `null`,
record the immutable image ID/digest and build version before attributing this
to a Dockerfile change. The 2026-11-02 supplied logs establish a roughly 22s
initialization gap followed by recovery, not persistent failure or a restart.

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
The script also asserts CE/EE image and effective container HEALTHCHECK metadata,
checks `readycheck` in single mode, runs fake-child supervisor faults in the
runtime image, and verifies a real packaged Wisp/Chen startup against an
unreachable Core fails within its budget without becoming healthy. This negative
test uses a disposable volume and a closed loopback port, not an existing Core.
Go tests use a real gRPC transport with controlled service responses to cover
delayed Wisp, an initially failing upstream read followed by recovery, hung RPCs,
startup timeout/cancellation, and readiness loss/recovery with stable liveness.
These tests do not replace the real managed fixture below.

`python3 scripts/test-managed-startup.py --chen data/bin/chen-go --wisp data/bin/wisp`
runs the actual native binaries and entrypoint against a disposable partial Core
stub with synthetic keys. Build them with `make build` and `./scripts/build-wisp.sh`.
It checks an initially refused Core connection followed by recovery, Chen's
short startup deadline, Wisp key-validation retry exhaustion, and graceful TERM.
Wisp v0.2.15 attempts registration/validation up to ten times with five-second
pauses: fast failures exhaust in about 50s, while request timeouts can take longer.
Chen's independent default budget is 120s. Increasing it does not extend Wisp's
policy. The image smoke similarly checks registration exhaustion with no stored
key. The stub deliberately rejects WebSocket upgrades: these are startup and
probe tests, not full Core authorization, database-session or audit acceptance.
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
