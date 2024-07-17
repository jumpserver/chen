FROM jumpserver/chen-base:latest AS stage-build
ENV LANG=en_US.UTF-8

WORKDIR /opt/chen/
COPY . .

RUN cd frontend \
    && npm run build

RUN mvn clean package -DskipTests

FROM debian:bullseye-slim

ARG DEPENDENCIES="                    \
        ca-certificates               \
        openjdk-17-jre-headless"

ARG APT_MIRROR=http://deb.debian.org
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
COPY entrypoint.sh drivers config ./

COPY --from=stage-build /usr/local/bin /usr/local/bin
COPY --from=stage-build /opt/chen/backend/web/target/web-*.jar /opt/chen/chen.jar

ARG VERSION=dev
ENV VERSION=$VERSION

VOLUME /opt/chen/data

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8082

STOPSIGNAL SIGQUIT

CMD [ "wisp" ]