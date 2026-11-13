# Chen

Chen is JumpServer's database workspace. It provides SQL queries, schema browsing,
result editing and data import/export through the Luna frontend. The Go backend
integrates with JumpServer through Wisp for authentication, permissions and audit.

## Build

Use the Go version specified in [go.mod](go.mod) and a C compiler. PostgreSQL SQL
parsing requires CGO. Build the frontend separately in the Luna repository.

```sh
make build
make vet
```

To build and run the container image:

```sh
make image-ee VERSION=dev PLATFORM=linux/arm64
make run
```

For first-time registration, set `BOOTSTRAP_TOKEN` in `chen/.env` to the token
configured in Core. `make run` and `make dev-up` read this file through Docker
Compose. With Core's default `auto` registration setting, start Chen within five
minutes of starting Core. Wisp saves its access key in the Chen data volume and
reuses it on subsequent starts.

Linux amd64 and arm64 builds include all supported database drivers, including
the pure Go Db2 driver.

## Run with JumpServer

Start Core and configure the bootstrap credential through the environment or a
private Wisp configuration file, then run:

```sh
CORE_HOST=http://127.0.0.1:8080 ./start.sh managed
```

Managed mode listens on `127.0.0.1:8082`. Keep registration keys in `data/keys`
and audit state in `data/go` persistent; container data is under `/opt/chen/data`.
The API is served at `/chen/api/v1/` with SSE streaming. Use `chen-go healthcheck`
for liveness and `chen-go readycheck` for readiness.

Queries have no default deadline or aggregate result byte quota. Deployments can
set `--query-timeout` and `--max-result-bytes`; `0` disables either limit. Cached
result sets retain up to `--max-rows` rows (10,000 by default).

## Run without JumpServer

Build Luna's standalone frontend using its declared Node.js and pnpm versions:

```sh
pnpm --dir ../luna install --frozen-lockfile
pnpm --dir ../luna generate:chen
mkdir -p data
cp conf/standalone_backends_example.yaml data/standalone_backends.yml
chmod 600 data/standalone_backends.yml
```

Edit the configuration with your database connection details, then start Chen:

```sh
make single BACKENDS=data/standalone_backends.yml WEB_DIR=../luna/.output/public
```

Open `http://127.0.0.1:8083/luna/chen/?selector=local-pg`, using a selector from
the [configuration](conf/standalone_backends_example.yaml). Single mode has no
Core authentication; keep access limited to the intended users. The selector
identifies a connection and is not a password.

## Release packages

`make package` extracts the built image into `build/chen-<version>-linux-<arch>.tar.gz`.
The archive includes binaries, configuration examples, licenses and this README.
On a matching Linux system, extract it and run `./start.sh managed`, or
`./start.sh single --backends /path/to/backends.yml --web-dir /path/to/luna`.
