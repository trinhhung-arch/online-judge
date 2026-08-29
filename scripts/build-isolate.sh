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

# Danh sách này KHÔNG phải đoán: nó là `LIBS=-lcap -lseccomp` và `-lsystemd` trong Makefile
# của isolate 2.6. asciidoc CỐ Ý không có — target `install` chỉ phụ thuộc $(PROGRAMS) và
# $(CONFIGS), không phụ thuộc man page, và asciidoc kéo theo cả bộ docbook (~200MB) cho một
# thứ không ai đọc trên máy chấm.
echo "==> Cài phụ thuộc build"
if command -v apt-get >/dev/null; then
    apt-get update -qq
    apt-get install -y -qq \
        git ca-certificates build-essential pkg-config \
        libcap-dev libseccomp-dev libsystemd-dev
elif command -v dnf >/dev/null; then
    dnf install -y -q git gcc make pkgconf-pkg-config \
        libcap-devel libseccomp-devel systemd-devel
else
    echo "Không nhận ra trình quản lý gói. Cài tay: libcap · libseccomp · libsystemd (bản -dev)" >&2
fi

# ★ Kiểm TRƯỚC khi make, và đây không phải cẩn thận thừa.
#
# Thiếu một header thì `make` đổ ra ~900 dòng rồi chết ở một dòng
# `fatal error: seccomp.h: No such file or directory` nằm lọt giữa. Đó đúng là cách CI đỏ lần
# đầu, và cách duy nhất tìm ra nguyên nhân là cuộn log tới dòng 928.
echo "==> Kiểm phụ thuộc"
missing=()
for probe in "sys/capability.h:libcap-dev" "seccomp.h:libseccomp-dev" "systemd/sd-daemon.h:libsystemd-dev"; do
    header="${probe%%:*}"
    package="${probe##*:}"
    if ! echo "#include <${header}>" | cc -fsyntax-only -xc - 2>/dev/null; then
        missing+=("${header} (gói ${package})")
    fi
done
if (( ${#missing[@]} )); then
    echo "Thiếu header, không build được:" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    exit 1
fi

echo "==> Lấy nguồn ${VERSION}"
rm -rf "${BUILD_DIR}"
git clone --depth 1 --branch "${VERSION}" https://github.com/ioi/isolate.git "${BUILD_DIR}"

# `make install` tự build cả ba chương trình ($(PROGRAMS): isolate · isolate-check-environment
# · isolate-cg-keeper) và cài kèm hai unit systemd. Gọi `make isolate` trước đó là thừa, và
# thừa một cách nguy hiểm: nó dễ làm người đọc tưởng cg-keeper không cần thiết.
echo "==> Build và cài vào ${PREFIX}"
make -C "${BUILD_DIR}" install PREFIX="${PREFIX}"

# isolate 2.x cần isolate-cg-keeper giữ một cgroup gốc được uỷ quyền; nó ghi đường dẫn ra
# /run/isolate/cgroup, đúng chỗ `cg_root = auto:` trong config trỏ tới. Thiếu nó thì --cg
# không ép được giới hạn nào.
if command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
    systemctl daemon-reload
    systemctl enable --now isolate.service \
        || echo "⚠️  isolate.service không khởi động được — kiểm chứng bên dưới sẽ nói rõ hơn"
else
    echo "⚠️  Không có systemd đang chạy. isolate-cg-keeper phải được khởi động bằng cách khác," >&2
    echo "    nếu không thì --cg sẽ hỏng." >&2
fi

echo "==> Kiểm chứng"
"${PREFIX}/bin/isolate" --version
# Cảnh báo, không chặn: isolate-check-environment than phiền về mọi thứ ảnh hưởng ĐỘ ỔN ĐỊNH
# CỦA SỐ ĐO (turbo boost, CPU governor). Trên runner dùng chung thì không sửa được, và không
# ca nào trong 14 test tấn công phụ thuộc vào số đo chính xác.
"${PREFIX}/bin/isolate-check-environment" --quiet \
    || echo "⚠️  Môi trường không lý tưởng cho phép ĐO, nhưng vẫn đủ cho phép CHẶN"

# Đây mới là bài kiểm thật: dựng và xoá được một box nghĩa là cgroup delegation hoạt động.
if ! "${PREFIX}/bin/isolate" --cg -b 999 --init >/dev/null; then
    echo "Không dựng được box. Gần như luôn là isolate-cg-keeper chưa chạy:" >&2
    echo "  systemctl status isolate.service" >&2
    echo "  cat /run/isolate/cgroup" >&2
    exit 1
fi
"${PREFIX}/bin/isolate" --cg -b 999 --cleanup

echo "OK. Chạy tiếp:  ./mvnw -pl oj-contract,oj-worker -am verify   (14/14 test tấn công phải xanh)"
