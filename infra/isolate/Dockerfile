# oj-worker + isolate. Build được cho cả linux/amd64 (máy dev WSL) lẫn linux/arm64 (host Mac).
#
#   docker buildx build --platform linux/arm64 -f infra/isolate/Dockerfile -t oj-worker:arm64 .
#
# ⚠️ ĐỌC TRƯỚC KHI CHẠY — container này KHÔNG chạy được với `docker run` mặc định.
#
# isolate cần quyền tạo namespace và cần một cgroup được uỷ quyền. Tối thiểu:
#
# Container KHÔNG có systemd, nên `isolate-cg-keeper` phải được khởi động bằng tay trước
# worker (nó là thứ ghi /run/isolate/cgroup mà `cg_root = auto:` trỏ tới):
#
#   /usr/local/sbin/isolate-cg-keeper &
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

# ★ PREFIX PHẢI LÀ ĐƯỜNG DẪN LÚC CHẠY, KHÔNG PHẢI ĐƯỜNG DẪN ĐỂ COPY.
#
# Makefile của isolate nhúng đường dẫn config vào binary NGAY LÚC BIÊN DỊCH:
#     CONFIG = $(PREFIX)/etc/isolate
#     config.o: CFLAGS += -DCONFIG_FILE='"$(CONFIG)"'
#
# Nên `install PREFIX=/opt/isolate` rồi copy sang /usr/local là dựng ra một binary đi tìm
# /opt/isolate/etc/isolate suốt đời. Triệu chứng đo được ngày 2026-09-05 trên host Mac:
#     Cannot open /opt/isolate/etc/isolate: No such file or directory
# rồi isolate-cg-keeper chết, /run/isolate/cgroup không bao giờ xuất hiện, và container tắt.
# `isolate --version` VẪN chạy được vì nó không đọc config — nên ca kiểm nào chỉ gọi
# --version sẽ báo xanh trên một cài đặt hỏng.
#
# Cách đúng: giữ PREFIX = đường dẫn thật (/usr/local, cũng là mặc định của Makefile) và
# dùng DESTDIR để dựng cây file ở chỗ khác cho dễ COPY. `install` của isolate có hỗ trợ
# DESTDIR: nó prepend $(DESTDIR) vào mọi đích, không đụng tới CONFIG đã biên dịch.
#
# `make install` tự build cả ba chương trình của $(PROGRAMS), gồm isolate-cg-keeper.
RUN git clone --depth 1 --branch ${ISOLATE_VERSION} https://github.com/ioi/isolate.git /src \
    && make -C /src install PREFIX=/usr/local DESTDIR=/staging

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

# ---------- stage 2b: JRE 21 cho CHÍNH worker ----------
#
# ★ VÌ SAO KHÔNG `apt-get install openjdk-21-jre-headless`
# Vì Debian bookworm KHÔNG CÓ gói đó. Bookworm dừng ở OpenJDK 17; openjdk-21 chỉ có từ
# trixie trở đi, và bookworm-backports cũng không có (kiểm trên packages.debian.org ngày
# 2026-09-05). Dòng cũ làm `docker buildx build` chết ngay tại đây với
#     E: Package 'openjdk-21-jre-headless' has no installation candidate
#
# ★ VÌ SAO KHÔNG NÂNG CẢ ẢNH LÊN trixie
# Trixie có openjdk-21 thật, nhưng nó kéo theo g++ 12 -> 14 và python3 3.11 -> 3.13. Hai
# thứ đó nằm TRONG box — đổi chúng là đổi môi trường chấm bài: đổi thời gian chạy (phải
# hiệu chuẩn lại host_factor), đổi nhãn `languages.version_label`, và bắt bộ 14 test tấn
# công phải chứng minh lại trên một toolchain khác với toolchain mà CI đã xanh.
#
# Worker chỉ cần Java 21 cho CHÍNH NÓ. Nên lấy JRE từ Temurin, giữ nguyên mọi thứ khác.
# glibc của jammy là 2.35, của bookworm là 2.36 — binary dựng cho 2.35 chạy trên 2.36 được,
# chiều ngược lại thì không. Đó là lý do KHÔNG dùng bản `-noble` (glibc 2.39).
FROM eclipse-temurin:21-jre-jammy AS jre

# ---------- stage 3: ảnh chạy ----------
FROM debian:bookworm-slim

# `default-jdk-headless` đã được gỡ. Nó ở đây để biên dịch bài nộp Java, nhưng trên bookworm
# nó là JDK 17 — không bao giờ khớp nhãn "OpenJDK 21" của bảng `languages`. Và dòng `java21`
# trong R__seed_du_lieu_tham_chieu.sql đang `enabled = FALSE` với các lý do đã ghi ở đó
# (`processes=1`, `/etc/alternatives` không mount trong box). Đây là lý do nữa, và nó phải
# được gỡ TRƯỚC khi ai đó bật lại `java21`: ảnh này không có `javac`.
RUN apt-get update && apt-get install -y --no-install-recommends \
        g++ python3 \
        libcap2 libseccomp2 libsystemd0 \
    && rm -rf /var/lib/apt/lists/*

# JVM của worker nằm ở /opt, CỐ Ý ngoài `oj.worker.sandbox.program-path` ([/usr/bin, /bin,
# /usr/local/bin]). Java của worker không phải là thứ được phép chạy bên trong box.
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

COPY --from=isolate-build /staging/usr/local/ /usr/local/

# ★ User `isolate` + dải subuid: config mặc định có `subid_user = isolate`, và cả `isolate`
# lẫn `isolate-cg-keeper` CHẾT ngay lúc đọc config nếu /etc/subuid không có dòng ấy
# (config.c: die("User %s not found in %s")). `make install` không tạo nó — đúng lỗi đã làm
# CI đỏ một lần. Dải này là nguồn uid riêng cho từng box (bất biến sandbox #7).
#
# setuid root cho isolate là bắt buộc — nó phải tạo được namespace. Worker thì KHÔNG chạy root.
RUN chmod u+s /usr/local/bin/isolate \
    && mkdir -p /var/local/lib/isolate /run/isolate/locks /var/tmp/oj-worker \
    && useradd --system --no-create-home --shell /usr/sbin/nologin isolate \
    && echo "isolate:200000:65536" >> /etc/subuid \
    && echo "isolate:200000:65536" >> /etc/subgid \
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
