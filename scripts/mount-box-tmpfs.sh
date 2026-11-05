#!/usr/bin/env bash
set -euo pipefail

BOX_ROOT="${BOX_ROOT:-/var/local/lib/isolate}"
SIZE="${SIZE:-8G}"

if [[ "${EUID}" -ne 0 ]]; then echo "Cần root." >&2; exit 1; fi

TOTAL_GB=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 / 1024 ))
WANT_GB=${SIZE%G}
if (( WANT_GB * 2 > TOTAL_GB )); then
    echo "Máy có ${TOTAL_GB}GB RAM, xin tmpfs ${SIZE}: quá nửa bộ nhớ. Từ chối." >&2
    echo "Đặt SIZE nhỏ hơn, ví dụ SIZE=2G ./scripts/mount-box-tmpfs.sh" >&2
    exit 1
fi

mkdir -p "${BOX_ROOT}"
if mountpoint -q "${BOX_ROOT}"; then
    echo "${BOX_ROOT} đã là mount point, không làm gì."
else
    mount -t tmpfs -o "size=${SIZE},mode=755,nosuid,nodev" tmpfs "${BOX_ROOT}"
    echo "Đã mount tmpfs ${SIZE} tại ${BOX_ROOT}"
fi

echo
echo "Để giữ sau khi khởi động lại, thêm vào /etc/fstab:"
echo "  tmpfs ${BOX_ROOT} tmpfs size=${SIZE},mode=755,nosuid,nodev 0 0"
df -h "${BOX_ROOT}" | tail -1
