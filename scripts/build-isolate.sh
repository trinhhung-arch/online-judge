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

# ★ isolate KHÔNG tự tạo user này, và `make install` cũng không.
#
# Config mặc định có `subid_user = isolate`: cả `isolate` lẫn `isolate-cg-keeper` đọc
# /etc/subuid và /etc/subgid để lấy dải uid cấp riêng cho từng box — mỗi box một uid, đó là
# bất biến sandbox #7 ("không chạy bằng root, mỗi box một uid riêng").
#
# Thiếu dòng ấy thì cả hai chết ngay lúc đọc config (config.c: die("User %s not found in %s")),
# và triệu chứng là HAI dòng lỗi trông không liên quan gì nhau:
#     Job for isolate.service failed because the control process exited with error code.
#     User isolate not found in /etc/subuid
# Một nguyên nhân, hai chỗ hỏng.
echo "==> User và dải subuid cho sandbox"
SUBID_COUNT="${SUBID_COUNT:-65536}"   # = số box tối đa; box id cao nhất dự án dùng là 999

if ! id -u isolate >/dev/null 2>&1; then
    nologin_shell=/usr/sbin/nologin
    [[ -x "${nologin_shell}" ]] || nologin_shell=/sbin/nologin
    [[ -x "${nologin_shell}" ]] || nologin_shell=/bin/false
    useradd --system --no-create-home --shell "${nologin_shell}" isolate
    echo "    tạo user hệ thống 'isolate'"
fi

# Chọn điểm bắt đầu nằm sau MỌI dải đã cấp, thay vì cắm cứng một con số. Hai user dùng chồng
# dải subuid nghĩa là box của isolate và container của người khác cùng một uid — một lỗi cách
# ly mà không có triệu chứng nào cho tới lúc bị khai thác.
subid_floor() {
    local floor=200000
    for file in /etc/subuid /etc/subgid; do
        [[ -f "${file}" ]] || continue
        floor=$(awk -F: -v floor="${floor}" \
            'NF>=3 && $1 != "isolate" { e = $2 + $3; if (e > floor) floor = e } END { print floor }' \
            "${file}")
    done
    echo "${floor}"
}

SUBID_START="${SUBID_START:-$(subid_floor)}"
for file in /etc/subuid /etc/subgid; do
    touch "${file}"
    if ! grep -q '^isolate:' "${file}"; then
        echo "isolate:${SUBID_START}:${SUBID_COUNT}" >> "${file}"
        echo "    ${file}: isolate:${SUBID_START}:${SUBID_COUNT}"
    fi
done

# isolate 2.x cần isolate-cg-keeper giữ một cgroup gốc được uỷ quyền; nó ghi đường dẫn ra
# /run/isolate/cgroup, đúng chỗ `cg_root = auto:` trong config trỏ tới. Thiếu nó thì --cg
# không ép được giới hạn nào.
# Sau khi qua được cf_parse(), cửa ải kế tiếp của cg-keeper là setup_cg(): nó mkdir một
# subgroup rồi ghi "+cpuset +memory" vào cgroup.subtree_control của cgroup được uỷ quyền.
# In sẵn danh sách controller ra đây để nếu bước sau hỏng thì câu trả lời đã nằm trong log.
echo "    cgroup controllers: $(cat /sys/fs/cgroup/cgroup.controllers 2>/dev/null || echo '?')"

if command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
    systemctl daemon-reload
    if ! systemctl enable --now isolate.service; then
        # Đổ thẳng nhật ký ra đây. Trên CI không ai gõ được `systemctl status`, nên một dòng
        # "xem systemctl status" là một dòng vô dụng — nó đúng là thứ đã làm lần đỏ trước tốn
        # thêm một vòng push.
        echo "⚠️  isolate.service không khởi động được. Nhật ký:" >&2
        systemctl status --no-pager --full isolate.service || true
        journalctl -xeu isolate.service --no-pager -n 50 || true
    fi
else
    echo "⚠️  Không có systemd đang chạy. isolate-cg-keeper phải được khởi động bằng cách khác," >&2
    echo "    nếu không thì --cg sẽ hỏng." >&2
fi

echo "==> Kiểm chứng"
"${PREFIX}/bin/isolate" --version
echo "    subuid: $(grep '^isolate:' /etc/subuid)"
echo "    subgid: $(grep '^isolate:' /etc/subgid)"
# isolate-check-environment gọi tput; runner không có TERM nên nó rắc 4 dòng
# `tput: unknown terminal "unknown"` vào giữa log.
export TERM="${TERM:-dumb}"
# Cảnh báo, không chặn: isolate-check-environment than phiền về mọi thứ ảnh hưởng ĐỘ ỔN ĐỊNH
# CỦA SỐ ĐO (turbo boost, CPU governor). Trên runner dùng chung thì không sửa được, và không
# ca nào trong 14 test tấn công phụ thuộc vào số đo chính xác.
"${PREFIX}/bin/isolate-check-environment" --quiet \
    || echo "⚠️  Môi trường không lý tưởng cho phép ĐO, nhưng vẫn đủ cho phép CHẶN"

# Đây mới là bài kiểm thật: dựng và xoá được một box nghĩa là cgroup delegation hoạt động.
if ! "${PREFIX}/bin/isolate" --cg -b 999 --init >/dev/null; then
    echo "Không dựng được box. Nhật ký:" >&2
    systemctl status --no-pager --full isolate.service 2>/dev/null || true
    journalctl -xeu isolate.service --no-pager -n 50 2>/dev/null || true
    echo "cg_root mà isolate đang trỏ tới: $(cat /run/isolate/cgroup 2>&1)" >&2
    exit 1
fi
"${PREFIX}/bin/isolate" --cg -b 999 --cleanup

echo "OK. Chạy tiếp:  ./mvnw -pl oj-contract,oj-worker -am verify   (14/14 test tấn công phải xanh)"
