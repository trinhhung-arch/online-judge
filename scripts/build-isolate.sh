#!/usr/bin/env bash
#
# Build và cài `isolate` TRÊN CHÍNH MÁY SẼ CHẤM BÀI. Bước 2.1 của docs/build-order.md.
#
# ⛔ KHÔNG BAO GIỜ copy binary isolate từ máy này sang máy khác.
#    `isolate` là chương trình setuid root nói chuyện trực tiếp với cgroup v2 và namespace của
#    kernel. Một binary dựng trên WSL x86 đem sang máy ARM thì không chạy; tệ hơn, một binary
#    dựng cho kernel khác có thể chạy được nhưng ép sai giới hạn — và "sandbox chạy nhưng ép
#    sai" là kịch bản không có triệu chứng nào cho tới lúc bị khai thác.
#
# Vì thế script này chạy được trên cả amd64 lẫn arm64 và luôn build từ nguồn:
#   - máy dev WSL (amd64)
#   - VM Linux ARM trên Mac M1 Max (arm64) — máy chấm chuẩn
#   - runner CI của GitHub Actions (amd64)
#
# Dùng:  sudo ./scripts/build-isolate.sh [phiên-bản]     (mặc định: v2.6)
set -euo pipefail

VERSION="${1:-v2.6}"
PREFIX="${PREFIX:-/usr/local}"
BUILD_DIR="${BUILD_DIR:-/tmp/isolate-build}"

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "isolate chỉ chạy trên Linux. Trên macOS thì chạy script này BÊN TRONG VM Linux," >&2
    echo "đó chính là lý do Bước 2.1 tồn tại." >&2
    exit 1
fi
if [[ "${EUID}" -ne 0 ]]; then
    echo "Cần root: isolate phải được cài setuid. Chạy lại bằng sudo." >&2
    exit 1
fi

echo "==> Kiến trúc: $(uname -m) · kernel $(uname -r)"

# cgroup v2 là điều kiện tiên quyết, không phải tuỳ chọn: giới hạn RAM và số tiến trình của
# CẢ nhóm (fork ra 50 con cũng không lách được) chỉ có ở v2.
if [[ "$(stat -fc %T /sys/fs/cgroup/)" != "cgroup2fs" ]]; then
    echo "Máy này chưa bật cgroup v2 (unified hierarchy). isolate 2.x bắt buộc có nó." >&2
    echo "Thêm systemd.unified_cgroup_hierarchy=1 vào cmdline rồi khởi động lại." >&2
    exit 1
fi

echo "==> Cài phụ thuộc build"
if command -v apt-get >/dev/null; then
    apt-get update -qq
    apt-get install -y -qq git build-essential pkg-config libcap-dev libsystemd-dev asciidoc
fi

echo "==> Lấy nguồn ${VERSION}"
rm -rf "${BUILD_DIR}"
git clone --depth 1 --branch "${VERSION}" https://github.com/ioi/isolate.git "${BUILD_DIR}"

echo "==> Build và cài vào ${PREFIX}"
make -C "${BUILD_DIR}" isolate isolate-check-environment
make -C "${BUILD_DIR}" install PREFIX="${PREFIX}"

# isolate 2.x cần một service giữ cgroup gốc được uỷ quyền; thiếu nó thì --cg im lặng không
# ép được giới hạn nào.
if command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
    systemctl daemon-reload
    systemctl enable --now isolate.service
fi

echo "==> Kiểm chứng"
"${PREFIX}/bin/isolate" --version
"${PREFIX}/bin/isolate" --cg -b 999 --init >/dev/null
"${PREFIX}/bin/isolate" --cg -b 999 --cleanup
echo "OK. Chạy tiếp:  ./mvnw -pl oj-worker verify   (14 test tấn công phải xanh 14/14)"
