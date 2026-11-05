#!/usr/bin/env bash
set -euo pipefail

goc="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kho="$goc/scripts/.secrets-dev"

if [ ! -f "$kho" ]; then
    umask 077
    {
        echo "export OJ_JWT_SECRET='$(openssl rand -base64 48)'"
        echo "export OJ_INTERNAL_SHARED_SECRET='$(openssl rand -hex 32)'"
        echo "export OJ_TOTP_KEY='$(openssl rand -base64 48)'"
    } > "$kho"
    echo "Đã sinh secret mới cho máy dev: scripts/.secrets-dev (đã gitignore)"
fi

if ! grep -q '^export OJ_TOTP_KEY=' "$kho"; then
    umask 077
    echo "export OJ_TOTP_KEY='$(openssl rand -base64 48)'" >> "$kho"
    echo "Đã bổ sung OJ_TOTP_KEY vào scripts/.secrets-dev (V11 — xác thực hai lớp)."
fi

. "$kho"

if [ "${#OJ_INTERNAL_SHARED_SECRET}" -lt 32 ]; then
    echo "OJ_INTERNAL_SHARED_SECRET trong $kho ngắn hơn 32 ký tự. Xoá file đó rồi chạy lại." >&2
    exit 1
fi
if [ "${#OJ_TOTP_KEY}" -lt 32 ] || [ "$OJ_TOTP_KEY" = "$OJ_JWT_SECRET" ]; then
    echo "OJ_TOTP_KEY trong $kho không hợp lệ: cần >= 32 ký tự và KHÁC OJ_JWT_SECRET." >&2
    echo "Xoá dòng đó trong $kho rồi chạy lại — script sẽ sinh khoá mới." >&2
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
