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

`make run` defaults to `ghcr.io/jumpserver/wisp:<chen-branch>`. The branch is
resolved from the current branch's tracking branch first, then from the local
branch. JumpServer-style branches such as `pr@new_terminal@feat_ai` resolve to
their base branch (`new_terminal`), and characters that cannot appear in a
Docker tag are replaced with `-`. A detached checkout falls back to `latest`.

Check the result without starting containers:

```bash
make dev-wisp-image
```

The matching tag must have been published in the Wisp container registry. If
it is not available, select a known tag with `WISP_TAG`, use `WISP_SOURCE` as
described below, or set `WISP_IMAGE` to a private-registry or locally built
image:

```bash
make run WISP_TAG=latest

make run \
  WISP_IMAGE=registry.example.com/wisp:my-branch \
  WISP_PULL_POLICY=always
```

Use `WISP_BRANCH` to resolve a tag from a branch other than the checked-out
Chen branch. `WISP_IMAGE_REPOSITORY` changes only the registry/repository while
retaining automatic tag selection. For an image already built locally, set
`WISP_PULL_POLICY=never`.

Calling `docker compose` directly (without the Makefile) retains the Compose
file's `ghcr.io/jumpserver/wisp:latest` fallback.

### Build from Wisp source

Add `docker-compose.wisp-source.yml` and set `WISP_SOURCE` to any local checkout
path. It does not need to be `../wisp`:

```bash
make run WISP_SOURCE=/absolute/path/to/wisp
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

With JDK 21 and Maven installed, use `make dev` to build and start Chen directly
on the host. Use `make run` to build and start Chen and Wisp together with
Compose. Use `make dev-up` for detached Compose startup, `make dev-logs` to
follow both service logs, and `make dev-down` to stop the environment. Run
`make help` for all development commands.

The build and runtime limits can be adjusted with `WISP_BUILD_PROCS`,
`WISP_BUILD_MEMORY`, `WISP_CPUS`, `WISP_MEMORY_LIMIT`, `CHEN_CPUS`, and
`CHEN_MEMORY_LIMIT`.
