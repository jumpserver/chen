FROM debian:bullseye-slim AS stage-wisp-build
ARG TARGETARCH

ARG DEPENDENCIES="                    \
        ca-certificates               \
        wget"

ARG APT_MIRROR=http://mirrors.ustc.edu.cn
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked,id=chen \
    --mount=type=cache,target=/var/lib/apt,sharing=locked,id=chen \
    set -ex \
    && rm -f /etc/apt/apt.conf.d/docker-clean \
    && echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' >/etc/apt/apt.conf.d/keep-cache \
    && sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list \
    && apt-get update \
    && apt-get -y install --no-install-recommends ${DEPENDENCIES} \
    && echo "no" | dpkg-reconfigure dash \
    && apt-get clean all \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt

ARG CHECK_VERSION=v1.0.3
RUN set -ex \
    && wget https://github.com/jumpserver-dev/healthcheck/releases/download/${CHECK_VERSION}/check-${CHECK_VERSION}-linux-${TARGETARCH}.tar.gz \
    && tar -xf check-${CHECK_VERSION}-linux-${TARGETARCH}.tar.gz \
    && mv check /usr/local/bin/ \
    && chown root:root /usr/local/bin/check \
    && chmod 755 /usr/local/bin/check \
    && rm -f check-${CHECK_VERSION}-linux-${TARGETARCH}.tar.gz

ARG WISP_VERSION=v0.1.22
RUN set -ex \
    && wget https://github.com/jumpserver/wisp/releases/download/${WISP_VERSION}/wisp-${WISP_VERSION}-linux-${TARGETARCH}.tar.gz \
    && tar -xf wisp-${WISP_VERSION}-linux-${TARGETARCH}.tar.gz -C /usr/local/bin/ --strip-components=1 \
    && chown root:root /usr/local/bin/wisp \
    && chmod 755 /usr/local/bin/wisp \
    && rm -f /opt/*.tar.gz

from node:16.20-bullseye-slim AS stage-web-build
ARG TARGETARCH
ARG NPM_REGISTRY="https://registry.npmmirror.com"

RUN set -ex \
    && npm config set registry ${NPM_REGISTRY} \
    && yarn config set registry ${NPM_REGISTRY}

WORKDIR /opt/chen/frontend

RUN --mount=type=cache,target=/usr/local/share/.cache/yarn,sharing=locked,id=chen \
    --mount=type=bind,source=frontend/package.json,target=package.json \
    --mount=type=bind,source=frontend/yarn.lock,target=yarn.lock \
    yarn install

ADD frontend .

RUN --mount=type=cache,target=/usr/local/share/.cache/yarn,sharing=locked,id=chen \
    yarn build

FROM debian:bullseye-slim AS stage-chen-build
ARG TARGETARCH

ARG DEPENDENCIES="                    \
        ca-certificates               \
        curl                          \
        openjdk-17-jre-headless"

ARG APT_MIRROR=http://mirrors.ustc.edu.cn
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked,id=chen \
    --mount=type=cache,target=/var/lib/apt,sharing=locked,id=chen \
    set -ex \
    && rm -f /etc/apt/apt.conf.d/docker-clean \
    && echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' >/etc/apt/apt.conf.d/keep-cache \
    && sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && echo "no" | dpkg-reconfigure dash

ARG MAVEN_VERSION=3.9.7
ARG USER_HOME_DIR="/root"
ARG BASE_URL=https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries

RUN mkdir -p /usr/share/maven /usr/share/maven/ref \
  && curl -fsSL -o /tmp/apache-maven.tar.gz ${BASE_URL}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
  && tar -xzf /tmp/apache-maven.tar.gz -C /usr/share/maven --strip-components=1 \
  && rm -f /tmp/apache-maven.tar.gz \
  && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

ENV MAVEN_HOME=/usr/share/maven
ENV MAVEN_CONFIG="$USER_HOME_DIR/.m2"

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

FROM debian:bullseye-slim
ARG TARGETARCH
ENV LANG=en_US.UTF-8

ARG DEPENDENCIES="                    \
        ca-certificates               \
        openjdk-17-jre-headless"

ARG APT_MIRROR=http://mirrors.ustc.edu.cn
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked,id=chen \
    --mount=type=cache,target=/var/lib/apt,sharing=locked,id=chen \
    sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list \
    && rm -f /etc/apt/apt.conf.d/docker-clean \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && echo "no" | dpkg-reconfigure dash \
    && sed -i "s@jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1@jdk.tls.disabledAlgorithms=SSLv3@" /etc/java-17-openjdk/security/java.security \
    && sed -i "s@# export @export @g" ~/.bashrc \
    && sed -i "s@# alias @alias @g" ~/.bashrc

WORKDIR /opt/chen

COPY --from=stage-wisp-build /usr/local/bin /usr/local/bin
COPY --from=stage-chen-build /opt/chen/backend/web/target/web-*.jar /opt/chen/chen.jar
COPY --from=stage-chen-build /opt/chen/entrypoint.sh .
COPY --from=stage-chen-build /opt/chen/drivers /opt/chen/drivers
COPY --from=stage-chen-build /opt/chen/config/application.yml /opt/chen/config/application.yml

ARG VERSION
ENV VERSION=$VERSION

VOLUME /opt/chen/data

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8082

STOPSIGNAL SIGQUIT

CMD [ "wisp" ]