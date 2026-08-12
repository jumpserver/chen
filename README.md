# Chen

Chen is the WebDB connection component of JumpServer, supporting multiple database protocols.

Chen is implemented in Java, and its name is derived from the Dota hero [Chen](https://www.dota2.com/hero/chen).

# UI Showcase

![UI Showcase](https://download.jumpserver.org/images/chen.png)


## Supported Features

- [x] Security Authentication
- [x] SQL Filtering
- [x] SQL Recording
- [x] SQL Blocking

## Supported Databases

- [x] MySQL 5.7/8.0+
- [x] MariaDB
- [x] PostgreSQL (X-Pack)
- [x] SQL Server (X-Pack)
- [x] Oracle (X-Pack)
- [x] DB2 (X-Pack)

## Develop with Wisp

The default Chen image contains a released Wisp binary. The development
override separates Wisp into its own service, starts Chen's Java process
directly, and connects Chen to `wisp:9090` over the Compose network.

### Use a Wisp image

`docker-compose.wisp-dev.yml` uses `ghcr.io/jumpserver/wisp:latest` by default.
Set `WISP_IMAGE` to use a branch image, private-registry image, or a locally
built image instead:

```bash
make dev \
  WISP_IMAGE=registry.example.com/wisp:my-branch \
  WISP_PULL_POLICY=always
```

For an image already built locally, set `WISP_PULL_POLICY=never`.

### Build from Wisp source

Add `docker-compose.wisp-source.yml` and set `WISP_SOURCE` to any local checkout
path. It does not need to be `../wisp`:

```bash
make dev WISP_SOURCE=/absolute/path/to/wisp
```

`WISP_SOURCE` is a BuildKit named context, so it may also be a Git context URL
when the changes have been pushed to a branch. Uncommitted changes require a
local directory.

After a Go-only Wisp change, rebuild just Wisp; the Java container can stay up
and its gRPC channel will reconnect:

```bash
make dev-wisp WISP_SOURCE=/absolute/path/to/wisp
```

If a Wisp `.proto` file changes, regenerate both Go and Java outputs in Wisp,
then synchronize the Java output before rebuilding Chen:

```bash
make -C /absolute/path/to/wisp proto-go proto-java
make proto-sync WISP_SOURCE=/absolute/path/to/wisp
make dev-up WISP_SOURCE=/absolute/path/to/wisp
```

Use `make dev-up` for detached startup, `make dev-logs` to follow both service
logs, and `make dev-down` to stop the environment. Run `make help` for all
development commands. `make dev run` is accepted as an alias of `make dev`.

The build and runtime limits can be adjusted with `WISP_BUILD_PROCS`,
`WISP_BUILD_MEMORY`, `WISP_CPUS`, `WISP_MEMORY_LIMIT`, `CHEN_CPUS`, and
`CHEN_MEMORY_LIMIT`.
