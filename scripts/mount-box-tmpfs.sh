#!/usr/bin/env bash
#
# Đặt thư mục box của isolate lên tmpfs. Bước 2.8 của docs/build-order.md.
#
# Ba lý do, xếp theo mức quan trọng:
#
#  1. GIỚI HẠN THẬT SỰ CÓ HIỆU LỰC. Trang tmpfs được tính vào cgroup của tiến trình cấp phát,
#     nên `--cg-mem` chặn luôn cả việc ghi file. Đo được: chương trình tạo 10.000 file trong
#     /box với trần 64MB — trên tmpfs nó chạm trần và bị chặn; trên ext4 thì 10MB ấy xuống
#     đĩa thật và cgroup không thấy gì (test tấn công 13).
#  2. Không mòn SSD. Sáu slot × vài nghìn bài mỗi ngày là rất nhiều lượt ghi-xoá.
#  3. Nhanh hơn: dọn box là xoá vùng nhớ, không phải xoá inode trên đĩa.
#
# Kích thước: 8GB trên host 64GB (nfrplan 2.2). KHÔNG chạy script này trên máy ít RAM — tmpfs
# lấy RAM thật, và một tmpfs 8GB trên máy 8GB là máy đứng.
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
    # mode=755: chỉ root ghi được vào gốc — chính isolate mới tạo thư mục con cho từng box.
    mount -t tmpfs -o "size=${SIZE},mode=755,nosuid,nodev" tmpfs "${BOX_ROOT}"
    echo "Đã mount tmpfs ${SIZE} tại ${BOX_ROOT}"
fi

echo
echo "Để giữ sau khi khởi động lại, thêm vào /etc/fstab:"
echo "  tmpfs ${BOX_ROOT} tmpfs size=${SIZE},mode=755,nosuid,nodev 0 0"
df -h "${BOX_ROOT}" | tail -1
