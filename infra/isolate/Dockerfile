# oj-worker + isolate. Build được cho cả linux/amd64 (máy dev WSL) lẫn linux/arm64 (host Mac).
#
#   docker buildx build --platform linux/arm64 -f infra/isolate/Dockerfile -t oj-worker:arm64 .
#
# ⚠️ ĐỌC TRƯỚC KHI CHẠY — container này KHÔNG chạy được với `docker run` mặc định.
#
# isolate cần quyền tạo namespace và cần một cgroup được uỷ quyền. Tối thiểu:
#
#   docker run --rm \
#     --cgroupns=private \
#     --security-opt seccomp=unconfined \
#     --security-opt apparmor=unconfined \
#     --cap-add SYS_ADMIN --cap-add SYS_RESOURCE --cap-add SYS_CHROOT \
#     --tmpfs /var/local/lib/isolate:size=8g,mode=755 \
#     oj-worker:arm64
#
# Đây là bề mặt tấn công thật, và nó có ý thức: nới quyền cho container là để SIẾT quyền
# của mã người dùng bên trong nó. Nếu bạn thấy mình đang gỡ bớt --cap-add cho "gọn", hãy
# chạy lại 14 test tấn công trước — vài ca sẽ chuyển từ "bị chặn" sang "không chạy được",
# và hai kết quả đó nhìn giống nhau trong log.
#
# KHÔNG BAO GIỜ copy binary isolate giữa hai kiến trúc: mỗi platform build lại từ nguồn ở
# stage dưới đây, đúng lý do đã ghi trong scripts/build-isolate.sh.

# ---------- stage 1: build isolate cho ĐÚNG kiến trúc đích ----------
FROM debian:bookworm-slim AS isolate-build

ARG ISOLATE_VERSION=v2.6
# Danh sách này khớp Makefile của isolate 2.6: `LIBS=-lcap -lseccomp` cho `isolate`, và
# `-lsystemd` cho `isolate-cg-keeper`. asciidoc CỐ Ý không có — target `install` không phụ
# thuộc man page.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates build-essential pkg-config \
        libcap-dev libseccomp-dev libsystemd-dev \
    && rm -rf /var/lib/apt/lists/*

# `make install` tự build cả ba chương trình của $(PROGRAMS), gồm isolate-cg-keeper.
RUN git clone --depth 1 --branch ${ISOLATE_VERSION} https://github.com/ioi/isolate.git /src \
    && make -C /src install PREFIX=/opt/isolate

# ---------- stage 2: build worker ----------
FROM maven:3.9-eclipse-temurin-21 AS worker-build
WORKDIR /build
COPY pom.xml .
COPY oj-contract/pom.xml oj-contract/
COPY oj-worker/pom.xml oj-worker/
COPY oj-api/pom.xml oj-api/
COPY oj-contract oj-contract
COPY oj-worker oj-worker
# -pl: không build oj-api, worker không cần nó (và nó cần Postgres để chạy test).
RUN mvn -B -q -pl oj-contract,oj-worker -am -DskipTests package

# ---------- stage 3: ảnh chạy ----------
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless \
        g++ python3 default-jdk-headless \
        libcap2 libseccomp2 libsystemd0 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=isolate-build /opt/isolate/ /usr/local/
# setuid root: bắt buộc, isolate phải tạo được namespace. Bản thân worker thì KHÔNG chạy root.
RUN chmod u+s /usr/local/bin/isolate \
    && mkdir -p /var/local/lib/isolate /run/isolate/locks /var/tmp/oj-worker \
    && useradd --system --create-home --uid 1500 ojworker \
    && chown ojworker:ojworker /var/tmp/oj-worker

COPY --from=worker-build /build/oj-worker/target/oj-worker-*.jar /app/oj-worker.jar

# Bất biến sandbox #7: worker chạy user thường; chỉ isolate mới lên root, và chỉ trong
# khoảnh khắc nó dựng box.
USER ojworker
ENV OJ_ISOLATE_BIN=/usr/local/bin/isolate \
    OJ_ISOLATE_BOX_ROOT=/var/local/lib/isolate \
    OJ_WORKER_CACHE_DIR=/var/tmp/oj-worker

ENTRYPOINT ["java", "-jar", "/app/oj-worker.jar"]
