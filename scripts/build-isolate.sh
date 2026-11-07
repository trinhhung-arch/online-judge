#!/usr/bin/env bash
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

if [[ "$(stat -fc %T /sys/fs/cgroup/)" != "cgroup2fs" ]]; then
    echo "Máy này chưa bật cgroup v2 (unified hierarchy). isolate 2.x bắt buộc có nó." >&2
    echo "Thêm systemd.unified_cgroup_hierarchy=1 vào cmdline rồi khởi động lại." >&2
    exit 1
fi

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

echo "==> Build và cài vào ${PREFIX}"
make -C "${BUILD_DIR}" install PREFIX="${PREFIX}"

echo "==> User và dải subuid cho sandbox"
SUBID_COUNT="${SUBID_COUNT:-65536}"

if ! id -u isolate >/dev/null 2>&1; then
    nologin_shell=/usr/sbin/nologin
    [[ -x "${nologin_shell}" ]] || nologin_shell=/sbin/nologin
    [[ -x "${nologin_shell}" ]] || nologin_shell=/bin/false
    useradd --system --no-create-home --shell "${nologin_shell}" isolate
    echo "    tạo user hệ thống 'isolate'"
fi

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

echo "    cgroup controllers: $(cat /sys/fs/cgroup/cgroup.controllers 2>/dev/null || echo '?')"

if command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
    systemctl daemon-reload
    if ! systemctl enable --now isolate.service; then
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
export TERM="${TERM:-dumb}"
"${PREFIX}/bin/isolate-check-environment" --quiet \
    || echo "⚠️  Môi trường không lý tưởng cho phép ĐO, nhưng vẫn đủ cho phép CHẶN"

if ! "${PREFIX}/bin/isolate" --cg -b 999 --init >/dev/null; then
    echo "Không dựng được box. Nhật ký:" >&2
    systemctl status --no-pager --full isolate.service 2>/dev/null || true
    journalctl -xeu isolate.service --no-pager -n 50 2>/dev/null || true
    echo "cg_root mà isolate đang trỏ tới: $(cat /run/isolate/cgroup 2>&1)" >&2
    exit 1
fi
"${PREFIX}/bin/isolate" --cg -b 999 --cleanup

echo "OK. Chạy tiếp:  ./mvnw -pl oj-contract,oj-worker -am verify   (14/14 test tấn công phải xanh)"
