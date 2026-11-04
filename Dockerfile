ARG CHEN_BASE_IMAGE=jumpserver/chen-base:20261103_075444
FROM ${CHEN_BASE_IMAGE} AS stage-build
ARG TARGETARCH
ARG VERSION=dev
WORKDIR /opt/chen
COPY go.mod go.sum ./
COPY third_party/ ./third_party/
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY cmd/ ./cmd/
COPY internal/ ./internal/
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build,id=chen-go-${TARGETARCH} \
    GOMAXPROCS=2 GOMEMLIMIT=1GiB CGO_ENABLED=1 go build -p=2 -trimpath \
      -ldflags="-s -w -X main.version=${VERSION}" -o /out/chen-go ./cmd/chen-go

FROM debian:bookworm-slim@sha256:7ff6ba31527b9e336162fa061746c366c4b2032f4d2cff2078bea43f78efe3f8
ARG APT_MIRROR=https://deb.debian.org
ARG VERSION=dev
COPY --from=stage-build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 chen && useradd --uid 10001 --gid chen --create-home chen \
    && mkdir -p /opt/chen/data/go && chown -R chen:chen /opt/chen
COPY --from=stage-build /out/chen-go /usr/local/bin/chen-go
COPY third_party/omni/LICENSE /opt/chen/licenses/omni/LICENSE
COPY third_party/go-db2/LICENSE third_party/go-db2/CHEN_PATCH.md /opt/chen/licenses/go-db2/
COPY --from=stage-build /usr/local/bin/wisp /usr/local/bin/wisp.version /usr/local/bin/
COPY --chmod=755 entrypoint.sh /opt/chen/entrypoint.sh
COPY conf/standalone_backends_example.yaml /opt/chen/conf/standalone_backends_example.yaml
RUN ldd /usr/local/bin/chen-go > /tmp/chen-ldd \
    && ! grep -q 'not found' /tmp/chen-ldd && rm /tmp/chen-ldd
USER 10001:10001
WORKDIR /opt/chen
ENV VERSION=${VERSION} CHEN_HTTP_ADDR=0.0.0.0:8082 CHEN_STATE_DIR=/opt/chen/data/go \
    WORK_DIR=/opt/chen COMPONENT_NAME=chen \
    LANG=C.UTF-8 LC_ALL=C.UTF-8
LABEL org.opencontainers.image.title="Chen" org.opencontainers.image.version="${VERSION}" \
    org.jumpserver.chen.db2="1"
VOLUME /opt/chen/data
EXPOSE 8082
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 CMD ["chen-go", "healthcheck"]
ENTRYPOINT ["/opt/chen/entrypoint.sh"]
CMD ["managed"]
