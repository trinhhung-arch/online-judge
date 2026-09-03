#!/usr/bin/env bash
#
# Chạy oj-api hoặc oj-worker trên máy dev, với secret ổn định.
#
#   ./scripts/chay-dev.sh api       # API trên cổng 8080
#   ./scripts/chay-dev.sh worker    # worker (cần isolate)
#
# ---------------------------------------------------------------------------------------
# VÌ SAO FILE NÀY TỒN TẠI
#
# Cách chạy tay có ba cái bẫy, và cả ba đều đã sập thật:
#
#  1. `export` chỉ sống trong MỘT terminal. Mở tab mới là mất, và triệu chứng là
#     "OJ_INTERNAL_SHARED_SECRET quá ngắn (cần >= 32 ký tự)" — một câu không hề gợi ý
#     rằng vấn đề là bạn đang ở tab khác.
#
#  2. Worker và API phải dùng CHUNG một `OJ_INTERNAL_SHARED_SECRET`. Sinh ngẫu nhiên ở
#     mỗi terminal thì worker gọi `claim` và nhận 401 mãi mãi, im lặng.
#
#  3. Thiếu `-Dspring-boot.run.profiles=dev` thì Flyway chặn khởi động với
#     "Detected applied migration not resolved locally: seed du lieu dev" — vì
#     `db/dev-seed/` chỉ nằm trong `locations` khi profile dev bật.
#
# File này sinh secret ĐÚNG MỘT LẦN vào `scripts/.secrets-dev` (đã gitignore) rồi dùng
# lại. Nhờ vậy hai terminal luôn khớp nhau, và mở tab mới không mất gì.
#
# Trên host thật thì KHÔNG dùng file này: secret ở đó đến từ biến môi trường của
# systemd/docker, và không bao giờ nằm trong repo.
# ---------------------------------------------------------------------------------------
set -euo pipefail

goc="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kho="$goc/scripts/.secrets-dev"

if [ ! -f "$kho" ]; then
    umask 077
    {
        echo "export OJ_JWT_SECRET='$(openssl rand -base64 48)'"
        echo "export OJ_INTERNAL_SHARED_SECRET='$(openssl rand -hex 32)'"
    } > "$kho"
    echo "Đã sinh secret mới cho máy dev: scripts/.secrets-dev (đã gitignore)"
fi

# shellcheck source=/dev/null
. "$kho"

# Kiểm ngay, đừng để Spring báo "quá ngắn" sau 40 giây khởi động.
if [ "${#OJ_INTERNAL_SHARED_SECRET}" -lt 32 ]; then
    echo "OJ_INTERNAL_SHARED_SECRET trong $kho ngắn hơn 32 ký tự. Xoá file đó rồi chạy lại." >&2
    exit 1
fi

case "${1:-}" in
    api)
        exec "$goc/mvnw" -pl oj-api spring-boot:run -Dspring-boot.run.profiles=dev
        ;;
    worker)
        exec "$goc/mvnw" -pl oj-worker spring-boot:run
        ;;
    *)
        echo "Dùng: ./scripts/chay-dev.sh {api|worker}" >&2
        exit 2
        ;;
esac
