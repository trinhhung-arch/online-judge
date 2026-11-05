FROM debian:bookworm-slim AS isolate-build

ARG ISOLATE_VERSION=v2.6
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates build-essential pkg-config \
        libcap-dev libseccomp-dev libsystemd-dev \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch ${ISOLATE_VERSION} https://github.com/ioi/isolate.git /src \
    && make -C /src install PREFIX=/usr/local DESTDIR=/staging

FROM maven:3.9-eclipse-temurin-21 AS worker-build
WORKDIR /build
COPY pom.xml .
COPY oj-contract/pom.xml oj-contract/
COPY oj-worker/pom.xml oj-worker/
COPY oj-api/pom.xml oj-api/
COPY oj-contract oj-contract
COPY oj-worker oj-worker
RUN mvn -B -q -pl oj-contract,oj-worker -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS jre

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        g++ python3 \
        libcap2 libseccomp2 libsystemd0 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

COPY --from=isolate-build /staging/usr/local/ /usr/local/

RUN chmod u+s /usr/local/bin/isolate \
    && mkdir -p /var/local/lib/isolate /run/isolate/locks /var/tmp/oj-worker \
    && useradd --system --no-create-home --shell /usr/sbin/nologin isolate \
    && echo "isolate:200000:65536" >> /etc/subuid \
    && echo "isolate:200000:65536" >> /etc/subgid \
    && useradd --system --create-home --uid 1500 ojworker \
    && chown ojworker:ojworker /var/tmp/oj-worker

COPY --from=worker-build /build/oj-worker/target/oj-worker-*.jar /app/oj-worker.jar

USER ojworker
ENV OJ_ISOLATE_BIN=/usr/local/bin/isolate \
    OJ_ISOLATE_BOX_ROOT=/var/local/lib/isolate \
    OJ_WORKER_CACHE_DIR=/var/tmp/oj-worker

ENTRYPOINT ["java", "-jar", "/app/oj-worker.jar"]
