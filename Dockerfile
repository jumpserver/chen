FROM jumpserver/chen-base:20260618_100547 AS stage-build
ENV LANG=en_US.UTF-8

WORKDIR /opt/chen/
COPY . .

RUN cd frontend \
    && npm run build

RUN mvn clean package -Dmaven.test.skip=true

FROM debian:trixie-slim

ARG DEPENDENCIES="                    \
        ca-certificates               \
        openjdk-21-jre-headless"

ARG APT_MIRROR=http://deb.debian.org

# DSA-6572-1: require the CA fix and refresh Java trust to remove stale roots.
RUN set -ex \
    && sed -i "s@http://.*.debian.org@${APT_MIRROR}@g" /etc/apt/sources.list.d/debian.sources \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && apt-get update \
    && apt-get install -y --no-install-recommends ${DEPENDENCIES} \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' ca-certificates)" ge 20250419+deb13u1 \
    && dpkg-trigger --no-await update-ca-certificates-java-fresh \
    && dpkg --triggers-only --pending \
    && echo "no" | dpkg-reconfigure dash \
    && sed -i "s@jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1@jdk.tls.disabledAlgorithms=SSLv3@" /etc/java-21-openjdk/security/java.security

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
