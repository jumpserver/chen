ARG CHEN_BASE_IMAGE=jumpserver/chen-base:go1.26.3-wisp0.2.15-chen1
FROM ${CHEN_BASE_IMAGE} AS stage-build
ARG TARGETARCH
ARG VERSION=dev
ARG WITH_DB2=0
WORKDIR /opt/chen
COPY go.mod go.sum ./
COPY third_party/ ./third_party/
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY cmd/ ./cmd/
COPY internal/ ./internal/
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build,id=chen-go-${TARGETARCH}-db2-${WITH_DB2} \
    set -eu; \
    tags=''; \
    if [ "$WITH_DB2" = 1 ]; then \
      test "$TARGETARCH" = amd64; test -f /opt/chen-deps/clidriver/include/sqlcli1.h; \
      export CGO_CFLAGS='-I/opt/chen-deps/clidriver/include'; \
      export CGO_LDFLAGS='-L/opt/chen-deps/clidriver/lib -Wl,-rpath,/opt/chen-deps/clidriver/lib'; \
      tags=ibmdb; \
    elif [ "$WITH_DB2" != 0 ]; then exit 1; fi; \
    GOMAXPROCS=2 GOMEMLIMIT=1GiB CGO_ENABLED=1 go build -p=2 -tags="$tags" -trimpath \
      -ldflags="-s -w -X main.version=${VERSION}" -o /out/chen-go ./cmd/chen-go

FROM debian:bookworm-slim@sha256:7ff6ba31527b9e336162fa061746c366c4b2032f4d2cff2078bea43f78efe3f8
ARG APT_MIRROR=https://deb.debian.org
ARG VERSION=dev
ARG WITH_DB2=0
COPY --from=stage-build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 libpam0g libxml2 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 chen && useradd --uid 10001 --gid chen --create-home chen \
    && mkdir -p /opt/chen/data/go && chown -R chen:chen /opt/chen
COPY --from=stage-build /out/chen-go /usr/local/bin/chen-go
COPY --from=stage-build /usr/local/bin/wisp /usr/local/bin/wisp.version /usr/local/bin/
COPY --from=stage-build /opt/chen-deps/ /opt/chen-deps/
COPY --chmod=755 entrypoint.sh /opt/chen/entrypoint.sh
COPY conf/standalone_backends_example.yaml /opt/chen/conf/standalone_backends_example.yaml
RUN ldd /usr/local/bin/chen-go > /tmp/chen-ldd \
    && ! grep -q 'not found' /tmp/chen-ldd && rm /tmp/chen-ldd
USER 10001:10001
WORKDIR /opt/chen
ENV VERSION=${VERSION} CHEN_HTTP_ADDR=0.0.0.0:8082 CHEN_STATE_DIR=/opt/chen/data/go \
    WORK_DIR=/opt/chen COMPONENT_NAME=chen \
    LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    LD_LIBRARY_PATH=/opt/chen-deps/clidriver/lib
LABEL org.opencontainers.image.title="Chen" org.opencontainers.image.version="${VERSION}" \
    org.jumpserver.chen.db2="${WITH_DB2}"
VOLUME /opt/chen/data
EXPOSE 8082
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 CMD ["chen-go", "healthcheck"]
ENTRYPOINT ["/opt/chen/entrypoint.sh"]
CMD ["managed"]
