# Chen

Chen is JumpServer's database workspace. The backend is Go; the Luna frontend
uses Monaco. Queries, catalog browsing, completion and cancellation use HTTP,
with one SSE stream per session for execution and control updates. Wisp connects
the managed service to JumpServer authentication, permissions, command approval,
session control, audit and heartbeats.

The workspace includes queries, inspection, draft-based grid editing, CSV import
and table/index DDL previews. Writes use the same JumpServer policy and audit
path. See [write workflows and limits](docs/writes.md).

## Run locally

Go 1.26.3 and a C compiler are required: PostgreSQL parsing uses `libpg_query`
through CGO. Node.js/pnpm are only needed when building Luna.

```sh
make build
make test vet
```

For managed mode, start Core and supply its bootstrap credential through your
private environment or a private Wisp configuration file. Never commit those
values. Existing Wisp registration is retained in `data/keys`.

```sh
CORE_HOST=http://127.0.0.1:8080 ./start.sh managed
```

`start.sh` builds Go and pinned Wisp v0.2.15. Managed mode listens on
`127.0.0.1:8082`; the HTTP API is under `/chen/api/v1/`. The startup script keeps
Wisp alive while Chen closes sessions and drains its audit outbox. Neither Java
nor Maven is part of the build or startup path.

## Run without Core

```sh
# In the adjacent Luna checkout:
pnpm install --frozen-lockfile
pnpm generate:chen

# In Chen:
mkdir -p data
cp conf/standalone_backends_example.yaml data/standalone_backends.yml
chmod 600 data/standalone_backends.yml
# Edit this private file with the database connection details.
make single BACKENDS=data/standalone_backends.yml WEB_DIR=../luna/.output/public
```

Open `http://127.0.0.1:8083/luna/chen/?selector=local-pg`, using a selector from
the configuration. Single mode does not contact Core or Wisp. Keep its listener
behind your intended access boundary; a selector is a routing name, not a secret.
See [single mode](docs/single.md).

## Build and run images

The formal build is `Dockerfile-base` → `Dockerfile` → `Dockerfile-ee`.
The default image contains Go and Wisp and retains the existing JumpServer
`chen:8082` endpoint and `/opt/chen/data` volume.

```sh
make image-ee VERSION=dev PLATFORM=linux/arm64
make run
# Or start in the background after providing Core configuration:
make dev-up
```

The standard image is supported on Linux amd64 and arm64. Db2 needs the IBM CLI
and is an explicit Linux amd64 variant:

```sh
make image-ee VERSION=dev WITH_DB2=1 PLATFORM=linux/amd64
```

Standard builds do not advertise Db2. The Db2 variant verifies a pinned IBM CLI
archive and includes its native runtime. Linux arm64 cannot be treated as Db2
capable without an official matching CLI distribution. See
[build, release and integration acceptance](docs/build.md) and
[database compatibility](docs/database-compatibility.md) for limits and evidence.

The old Java sources remain for migration reference. They are not used by the
current Makefile, container build, start script or release workflow.

## Development and integration

- [Go backend architecture and APIs](docs/go-v1.md)
- [JumpServer integration, approvals and validation boundaries](internal/jumpserver/README.md)
- [SQL analysis and completion](internal/sqlservice/README.md)
- [Build and integration acceptance](docs/build.md)

Luna is a separate repository. Build its corresponding revision into docker-web;
rebuilding Chen does not update the browser's static assets. The normal managed
route, single route, web application and Electron integration must all select
the same backend generation before consuming a one-use connection token.
