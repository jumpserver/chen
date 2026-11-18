# syntax=docker/dockerfile:1

from node:16.20-bullseye-slim as stage-web-build
ARG TARGETARCH
ARG NPM_REGISTRY="https://registry.npmmirror.com"

RUN set -ex \
    && npm config set registry ${NPM_REGISTRY} \
    && yarn config set registry ${NPM_REGISTRY}

WORKDIR /opt/chen/frontend
ADD frontend/package.json frontend/yarn.lock .
RUN --mount=type=cache,target=/usr/local/share/.cache/yarn,sharing=locked,id=chen \
    yarn install

ADD frontend .
RUN --mount=type=cache,target=/usr/local/share/.cache/yarn,sharing=locked,id=chen \
    yarn build

FROM registry.fit2cloud.com/jumpserver/maven:3.9.5-openjdk-17-slim-bullseye as stage-chen-build
ARG TARGETARCH

WORKDIR /opt/chen

COPY . .
COPY --from=stage-web-build /opt/chen/frontend/dist frontend/dist

ARG VERSION
ENV VERSION=$VERSION

ARG MAVEN_MIRROR=https://repo.maven.apache.org/maven2

RUN --mount=type=cache,target=/root/.m2,id=chen \
    set -ex \
    && mkdir -p /root/.m2 \
    && sed -i "s@https://repo.maven.apache.org/maven2@${MAVEN_MIRROR}@g" settings.xml \
    && \cp -f settings.xml /root/.m2/ \
    && mvn clean package -DskipTests

RUN chmod +x entrypoint.sh

# Corretto 17.0.21.1.2 includes the fix for CVE-2026-114938.
FROM scratch AS corretto-amd64
ADD --checksum=sha256:d8a350b5988f58e6186ddc1db584af170e9d56862816d4a578e9570d85307e2c \
    https://corretto.aws/downloads/resources/17.0.21.1.2/amazon-corretto-17.0.21.1.2-linux-x64.tar.gz /corretto.tar.gz

FROM scratch AS corretto-arm64
ADD --checksum=sha256:f58bea1d8fca5788081a438e46997af0fe62491ef29b6ba4d9258cbfe8eeb5bb \
    https://corretto.aws/downloads/resources/17.0.21.1.2/amazon-corretto-17.0.21.1.2-linux-aarch64.tar.gz /corretto.tar.gz

FROM corretto-${TARGETARCH} AS stage-corretto

FROM debian:bullseye-slim
ARG TARGETARCH
ENV LANG=zh_CN.UTF-8
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

ARG DEPENDENCIES="                    \
        ca-certificates               \
        curl                          \
        locales                       \
        p11-kit                       \
        tzdata                        \
        wget"

ARG APT_MIRROR=http://mirrors.ustc.edu.cn
# Bullseye security packages are served from the Debian archive.
# CVE-2026-91403: distrust affected roots; docker-openjdk also refreshes Java trust.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked,id=chen \
    --mount=type=bind,from=stage-corretto,source=/corretto.tar.gz,target=/tmp/corretto.tar.gz \
    set -ex \
    && mkdir -p "${JAVA_HOME}" \
    && tar -xzf /tmp/corretto.tar.gz --strip-components=1 --no-same-owner -C "${JAVA_HOME}" \
    && sed -i \
        -e "s@http://.*.debian.org@${APT_MIRROR}@g" \
        -e 's@^deb [^ ]*/debian-security bullseye-security @deb http://archive.debian.org/debian-security bullseye-security @' \
        /etc/apt/sources.list \
    && sed -i 's/^jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1,/jdk.tls.disabledAlgorithms=SSLv3,/' "${JAVA_HOME}/conf/security/java.security" \
    && rm -f /etc/apt/apt.conf.d/docker-clean \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && sed -i \
        -e 's|^mozilla/emSign_ECC_Root_CA_-_G3.crt$|!&|' \
        -e 's|^mozilla/Comodo_AAA_Services_root.crt$|!&|' \
        /etc/ca-certificates.conf \
    && update-ca-certificates --fresh \
    && printf '%s\n' '#!/bin/sh' \
        'exec trust extract --overwrite --format=java-cacerts --filter=ca-anchors --purpose=server-auth "$JAVA_HOME/lib/security/cacerts"' \
        > /etc/ca-certificates/update.d/docker-openjdk \
    && chmod +x /etc/ca-certificates/update.d/docker-openjdk \
    && /etc/ca-certificates/update.d/docker-openjdk \
    && keytool -list -cacerts -storepass changeit >/dev/null \
    && echo "no" | dpkg-reconfigure dash \
    && sed -i 's/^# \(zh_CN.UTF-8 UTF-8\)$/\1/' /etc/locale.gen \
    && locale-gen \
    && apt-get clean all \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt

ARG WISP_VERSION=v0.1.21
RUN set -ex \
    && wget https://github.com/jumpserver/wisp/releases/download/${WISP_VERSION}/wisp-${WISP_VERSION}-linux-${TARGETARCH}.tar.gz \
    && tar -xf wisp-${WISP_VERSION}-linux-${TARGETARCH}.tar.gz -C /usr/local/bin/ --strip-components=1 \
    && chown root:root /usr/local/bin/wisp \
    && chmod 755 /usr/local/bin/wisp \
    && rm -f /opt/*.tar.gz

WORKDIR /opt/chen

COPY --from=stage-chen-build /opt/chen/backend/web/target/web-*.jar /opt/chen/chen.jar
COPY --from=stage-chen-build /opt/chen/entrypoint.sh .
COPY --from=stage-chen-build /opt/chen/drivers /opt/chen/drivers
COPY --from=stage-chen-build /opt/chen/config/application.yml /opt/chen/config/application.yml
COPY --from=stage-chen-build /opt/chen/config/logback.xml /opt/chen/config/logback.xml

ARG VERSION
ENV VERSION=$VERSION

EXPOSE 8082

CMD ["./entrypoint.sh"]
