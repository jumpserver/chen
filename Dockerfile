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

FROM registry.fit2cloud.com/jumpserver/openjdk:17-slim-bullseye
ARG TARGETARCH
ENV LANG=zh_CN.UTF-8

ARG DEPENDENCIES="                    \
        ca-certificates               \
        curl                          \
        locales                       \
        wget"

ARG APT_MIRROR=http://mirrors.ustc.edu.cn
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked,id=chen \
    sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list \
    && sed -i "s@jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1@jdk.tls.disabledAlgorithms=SSLv3@" /opt/java/openjdk/conf/security/java.security \
    || sed -i "s@jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1@jdk.tls.disabledAlgorithms=SSLv3@" /usr/local/openjdk-17/conf/security/java.security \
    && rm -f /etc/apt/apt.conf.d/docker-clean \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && echo "no" | dpkg-reconfigure dash \
    && echo "zh_CN.UTF-8" | dpkg-reconfigure locales \
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

ARG VERSION
ENV VERSION=$VERSION

EXPOSE 8082

CMD ["./entrypoint.sh"]
