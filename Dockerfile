FROM jumpserver/chen-base:20251010_105539 AS stage-build
ENV LANG=en_US.UTF-8

WORKDIR /opt/chen/
COPY . .

RUN cd frontend \
    && npm run build

RUN mvn clean package -Dmaven.test.skip=true

FROM debian:bullseye-slim

ARG DEPENDENCIES="                    \
        ca-certificates               \
        openjdk-17-jre-headless"

ARG APT_MIRROR=http://deb.debian.org

RUN set -ex \
    && sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && echo "no" | dpkg-reconfigure dash \
    && sed -i "s@jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1@jdk.tls.disabledAlgorithms=SSLv3@" /etc/java-17-openjdk/security/java.security

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
