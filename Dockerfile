# syntax=docker/dockerfile:1

FROM jumpserver/chen-base:20260821_111128 AS stage-build
ENV LANG=en_US.UTF-8

WORKDIR /opt/chen/
COPY . .

RUN cd frontend \
    && npm run build

RUN mvn clean package -Dmaven.test.skip=true

# Corretto 21.0.13.1.2 includes the fix for CVE-2026-114938.
FROM scratch AS corretto-amd64
ADD --checksum=sha256:2aaa2a9a43143b20ff0b28c8d0da27407b97dc40b160d9a2f60a26965815e948 \
    https://corretto.aws/downloads/resources/21.0.13.1.2/amazon-corretto-21.0.13.1.2-linux-x64.tar.gz /corretto.tar.gz

FROM scratch AS corretto-arm64
ADD --checksum=sha256:55751c47c7e3be3b33af43ccdcf3a6f4814d308390b7400477594d421bd16a53 \
    https://corretto.aws/downloads/resources/21.0.13.1.2/amazon-corretto-21.0.13.1.2-linux-aarch64.tar.gz /corretto.tar.gz

FROM corretto-${TARGETARCH} AS stage-corretto

FROM debian:trixie-slim

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

ARG DEPENDENCIES="                    \
        ca-certificates               \
        ca-certificates-java"

ARG APT_MIRROR=http://deb.debian.org

# DSA-6572-1: require the CA fix and refresh Java trust to remove stale roots.
RUN --mount=type=bind,from=stage-corretto,source=/corretto.tar.gz,target=/tmp/corretto.tar.gz \
    set -ex \
    && mkdir -p "${JAVA_HOME}" \
    && tar -xzf /tmp/corretto.tar.gz --strip-components=1 -C "${JAVA_HOME}" \
    && sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list.d/debian.sources \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' ca-certificates)" ge 20250419+deb13u1 \
    && ln -sf /etc/ssl/certs/java/cacerts "${JAVA_HOME}/lib/security/cacerts" \
    && dpkg-trigger --no-await update-ca-certificates-java-fresh \
    && dpkg --triggers-only --pending \
    && keytool -list -cacerts -storepass changeit >/dev/null \
    && echo "no" | dpkg-reconfigure dash \
    && sed -i 's/^jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1,/jdk.tls.disabledAlgorithms=SSLv3,/' "${JAVA_HOME}/conf/security/java.security"

WORKDIR /opt/chen

COPY --from=stage-build /usr/local/bin/check /usr/local/bin/wisp /usr/local/bin/
COPY --from=stage-build /opt/chen/backend/web/target/web-*.jar /opt/chen/chen.jar
COPY --from=stage-build /opt/chen/entrypoint.sh .
COPY --from=stage-build /opt/chen/drivers /opt/chen/drivers
COPY --from=stage-build /opt/chen/config/application.yml /opt/chen/config/application.yml

ARG VERSION=dev
ENV VERSION=$VERSION

VOLUME /opt/chen/data

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8082

STOPSIGNAL SIGQUIT

CMD [ "wisp" ]
