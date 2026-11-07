#!/usr/bin/env bash
set -euo pipefail

ANH=${ANH:-oj-worker:arm64}
TEN=${TEN:-oj-worker}
NEN_TANG=${NEN_TANG:-linux/arm64}
GOC=$(cd "$(dirname "$0")/.." && pwd)

API=${OJ_API_BASE_URL:-http://host.docker.internal:8080}
RABBIT=${OJ_RABBIT_HOST:-host.docker.internal}
SLOTS=${OJ_WORKER_SLOTS:-6}
HOST_NAME=${OJ_WORKER_HOST_NAME:-mac-m1max-host}
TMPFS=${OJ_BOX_TMPFS:-8g}

chi_kiem=0; khong_build=0
for a in "$@"; do case "$a" in
    --chi-kiem)    chi_kiem=1 ;;
    --khong-build) khong_build=1 ;;
    --xoa)         docker rm -f "$TEN" 2>/dev/null && echo "Đã xoá $TEN." || echo "Không có $TEN."; exit 0 ;;
    *) echo "Tham số lạ: $a" >&2; exit 2 ;;
esac; done

loi() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }
luu_y(){ echo "  ! $*"; }

echo "── Kiểm tiền đề ──"

[ "$(uname -s)" = "Darwin" ] || luu_y "Không phải macOS ($(uname -s)) — script vẫn chạy được, nhưng nó viết cho Mac."

command -v docker >/dev/null || loi "Chưa có Docker. Cài Docker Desktop hoặc OrbStack."
docker buildx version >/dev/null 2>&1 || loi "Chưa có docker buildx — cần nó để build đúng $NEN_TANG."
docker info >/dev/null 2>&1 || loi "Docker chưa chạy."
ok "docker + buildx"

arch_vm=$(docker info --format '{{.Architecture}}' 2>/dev/null || echo '?')
case "$arch_vm" in
    aarch64|arm64) ok "VM Docker là arm64" ;;
    *) luu_y "VM Docker là '$arch_vm', không phải arm64. Ảnh $NEN_TANG sẽ chạy qua giả lập"
       luu_y "và MỌI con số thời gian đo được sẽ sai. Không dùng máy này làm máy chấm chuẩn." ;;
esac

ram_byte=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
ram_gb=$(( ram_byte / 1024 / 1024 / 1024 ))
tmpfs_gb=${TMPFS%g}
if [ "$ram_gb" -lt $(( tmpfs_gb + 4 )) ]; then
    luu_y "VM chỉ có ${ram_gb}GB RAM mà tmpfs box xin ${tmpfs_gb}GB."
    luu_y "Tăng RAM cho VM, hoặc đặt OJ_BOX_TMPFS=2g. Thiếu RAM thì biên dịch chết giữa chừng."
else
    ok "VM có ${ram_gb}GB RAM (tmpfs box ${tmpfs_gb}GB)"
fi

[ -n "${OJ_INTERNAL_SHARED_SECRET:-}" ] || loi "Thiếu OJ_INTERNAL_SHARED_SECRET — worker không gọi được /internal/judge/*."
[ ${#OJ_INTERNAL_SHARED_SECRET} -ge 32 ] || loi "OJ_INTERNAL_SHARED_SECRET chỉ ${#OJ_INTERNAL_SHARED_SECRET} ký tự, cần ≥ 32."
ok "OJ_INTERNAL_SHARED_SECRET (${#OJ_INTERNAL_SHARED_SECRET} ký tự)"

[ -n "${OJ_RABBIT_PASSWORD:-}" ] || loi "Thiếu OJ_RABBIT_PASSWORD — lấy từ .env, cùng giá trị oj-api đang dùng."
ok "OJ_RABBIT_PASSWORD"

for cap in "OJ_API_BASE_URL=$API" "OJ_RABBIT_HOST=$RABBIT"; do
    case "${cap#*=}" in
        *localhost*|*127.0.0.1*)
            loi "$cap — 'localhost' bên trong container là CHÍNH CONTAINER, không phải máy Mac.
     Gần như chắc chắn shell của bạn đã source .env (nơi giá trị này ĐÚNG cho oj-api chạy
     thẳng trên máy). Container cần đi qua host-gateway. Chạy lại với hai biến ép rõ:

       OJ_API_BASE_URL=http://host.docker.internal:8080 \\
       OJ_RABBIT_HOST=host.docker.internal \\
           ./scripts/trien-khai-mac.sh ${*:-}" ;;
    esac
done
ok "địa chỉ API và RabbitMQ dùng được từ trong container"

ref_cpu=${OJ_HOST_REFERENCE_CPU_MS:-0}
case "$ref_cpu" in
    ''|*[!0-9]*)
        loi "OJ_HOST_REFERENCE_CPU_MS='$ref_cpu' không phải số nguyên ≥ 0.
     Đây là số mili-giây CPU ĐO ĐƯỢC TRÊN CHÍNH MÁY NÀY, không phải chữ để dán.
     Lấy nó ra bằng:  docker logs $TEN 2>&1 | grep 'Đo máy'
     Chưa có số thì BỎ HẲN biến đi — mặc định 0 nghĩa là chưa hiệu chuẩn, vẫn chạy được." ;;
esac
if [ "$ref_cpu" = 0 ]; then
    luu_y "OJ_HOST_REFERENCE_CPU_MS=0 — CHƯA hiệu chuẩn. Giới hạn thời gian vẫn quy chiếu máy cũ."
else
    ok "OJ_HOST_REFERENCE_CPU_MS=${ref_cpu}ms"
fi

if curl -fsS --max-time 3 "${API/host.docker.internal/localhost}/api/v1/status" >/dev/null 2>&1; then
    ok "API trả lời tại ${API/host.docker.internal/localhost}"
else
    luu_y "Chưa gọi được API. Worker khởi động trước API là chuyện thường (missing-queues-fatal: false),"
    luu_y "nhưng nếu API không bao giờ lên thì worker sẽ chờ mãi mà không báo lỗi to."
fi

[ "$chi_kiem" -eq 1 ] && { echo; echo "Chỉ kiểm tiền đề — dừng ở đây."; exit 0; }

if [ "$khong_build" -eq 0 ]; then
    echo
    echo "── Build $ANH cho $NEN_TANG ──"
    docker buildx build \
        --platform "$NEN_TANG" \
        -f "$GOC/infra/isolate/Dockerfile" \
        -t "$ANH" --load "$GOC"
    ok "đã build $ANH"
fi

echo
echo "── Chạy $TEN ──"
docker rm -f "$TEN" >/dev/null 2>&1 || true

KHOI_DONG='set -e
mount -o remount,rw /sys/fs/cgroup
mkdir -p /sys/fs/cgroup/init /sys/fs/cgroup/boxes /run/isolate
for p in $(cat /sys/fs/cgroup/cgroup.procs); do echo $p > /sys/fs/cgroup/init/cgroup.procs 2>/dev/null || true; done
for c in cpuset cpu memory pids; do
  echo +$c > /sys/fs/cgroup/cgroup.subtree_control 2>/dev/null || true
  echo +$c > /sys/fs/cgroup/boxes/cgroup.subtree_control 2>/dev/null || true
done
grep -q memory /sys/fs/cgroup/boxes/cgroup.subtree_control || { echo "khong bat duoc controller memory cho /sys/fs/cgroup/boxes" >&2; exit 1; }
echo /sys/fs/cgroup/boxes > /run/isolate/cgroup
exec setpriv --reuid=1500 --regid=1500 --init-groups java -jar /app/oj-worker.jar'

docker run -d --name "$TEN" \
    --restart unless-stopped \
    --user root \
    --entrypoint /bin/sh \
    --cgroupns=private \
    --cap-add SYS_ADMIN --cap-add SYS_RESOURCE --cap-add SYS_CHROOT --cap-add NET_ADMIN \
    --tmpfs "/var/local/lib/isolate:size=$TMPFS,mode=755" \
    --add-host host.docker.internal:host-gateway \
    -e OJ_INTERNAL_SHARED_SECRET="$OJ_INTERNAL_SHARED_SECRET" \
    -e OJ_API_BASE_URL="$API" \
    -e OJ_RABBIT_HOST="$RABBIT" \
    -e OJ_RABBIT_PORT="${OJ_RABBIT_PORT:-5672}" \
    -e OJ_RABBIT_USER="${OJ_RABBIT_USER:-ojuser}" \
    -e OJ_RABBIT_PASSWORD="$OJ_RABBIT_PASSWORD" \
    -e OJ_WORKER_HOST_NAME="$HOST_NAME" \
    -e OJ_WORKER_ARCH=arm64 \
    -e OJ_WORKER_SLOTS="$SLOTS" \
    -e OJ_HOST_REFERENCE_CPU_MS="$ref_cpu" \
    "$ANH" -c "$KHOI_DONG" >/dev/null
len_jvm=0
printf '  đợi JVM lên'
for _ in $(seq 40); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$TEN" 2>/dev/null)" != true ]; then
        ma=$(docker inspect -f '{{.State.ExitCode}}' "$TEN" 2>/dev/null || echo '?')
        printf '\n'
        echo "── 30 dòng cuối nhật ký $TEN ──" >&2
        docker logs --tail 30 "$TEN" 2>&1 | sed 's/^/  /' >&2
        echo >&2
        loi "container thoát với mã $ma trước khi JVM lên.
     Không kiểm gì thêm: mọi ca kiểm bên dưới sẽ hỏng vì đúng một lý do này."
    fi
    if docker logs "$TEN" 2>&1 | grep "Started OjWorkerApplication" >/dev/null; then len_jvm=1; break; fi
    printf '.'
    sleep 1
done
printf '\n'
if [ "$len_jvm" -eq 1 ]; then
    ok "container đã chạy — JVM đã lên"
else
    luu_y "container còn sống nhưng sau 40s chưa thấy 'Started OjWorkerApplication'."
    luu_y "Ca kiểm dưới đây vẫn chạy, nhưng 'JVM chạy dưới ojworker' hỏng thì nhiều khả năng"
    luu_y "là vì JVM chưa kịp lên, KHÔNG phải vì sai quyền. Đọc: docker logs $TEN"
fi

echo
echo "── Kiểm bên trong container ──"
sleep 3
kiem() {
    if docker exec "$TEN" sh -c "$2" >/dev/null 2>&1; then ok "$1"; else luu_y "$1 — KHÔNG đạt"; fi
}
kiem "cgroup v2 (unified hierarchy)" '[ "$(stat -fc %T /sys/fs/cgroup)" = cgroup2fs ]'
kiem "isolate chạy được"             '/usr/local/bin/isolate --version'
kiem "isolate dựng được box CÓ giới hạn RAM" '/usr/local/bin/isolate --cg -b 90 --init >/dev/null 2>&1 && [ -f /sys/fs/cgroup/boxes/box-90/memory.max ]; r=$?; /usr/local/bin/isolate --cg -b 90 --cleanup >/dev/null 2>&1; exit $r'
kiem "isolate là setuid root"        '[ -u /usr/local/bin/isolate ]'
kiem "/etc/subuid có dòng isolate"   'grep -q "^isolate:" /etc/subuid'
kiem "cgroup boxes có controller memory+pids" 'grep -q memory /sys/fs/cgroup/boxes/cgroup.subtree_control && grep -q pids /sys/fs/cgroup/boxes/cgroup.subtree_control'
kiem "JVM chạy dưới ojworker (uid 1500)" \
    'for p in /proc/[0-9]*; do [ "$(cat $p/comm 2>/dev/null)" = java ] \
        && grep -qE "^Uid:[[:space:]]+1500" $p/status && exit 0; done; exit 1'

echo
echo "── Hai việc BẮT BUỘC làm tiếp, đừng bỏ ──"
cat <<'TIEP'

  1. HIỆU CHUẨN host_factor.
     ADR 006: "Giới hạn thời gian của đề phải hiệu chuẩn lại nếu đổi máy chấm
     chuẩn." Worker tự chạy benchmark 15 phút một lần, nhưng OJ_HOST_REFERENCE_CPU_MS
     mặc định là 0 = CHƯA hiệu chuẩn — nó đo và cảnh báo throttle, nhưng không
     đổi giới hạn. Bỏ qua bước này = mọi giới hạn thời gian vẫn tính theo máy WSL x86.

     Hai lệnh, đúng thứ tự. Lệnh đầu IN RA một con số; lệnh sau dán chính con
     số ấy vào — không phải chữ trong dấu ngoặc:

       docker logs oj-worker 2>&1 | grep -E "Đo máy|Hiệu chuẩn máy" | tail -2
       . scripts/.secrets-dev && OJ_HOST_REFERENCE_CPU_MS=<số vừa in> \
           ./scripts/trien-khai-mac.sh --khong-build

     Ra "Hiệu chuẩn máy ... host_factor = 1.000" nghĩa là đã xong từ trước.

  2. CHẠY LẠI 14 TEST TẤN CÔNG SANDBOX.
     ./scripts/kiem-sandbox.sh
     CLAUDE.md mục 6 đòi điều này mỗi khi đụng sandbox, và đổi kiến trúc máy
     CHÍNH LÀ đụng sandbox. Cái bẫy: khi nới/siết quyền container, vài ca chuyển
     từ "bị chặn" sang "không chạy được" — và hai kết quả đó nhìn giống hệt nhau
     trong log.

     ★ ĐỪNG gọi thẳng `./mvnw -pl oj-worker verify -Dit.test=SandboxAttackIT`
       trên máy Mac. isolate cần cgroup v2 + namespace Linux, nên trên macOS
       JUnit huỷ cả class và Maven in "Tests run: 0" kèm BUILD SUCCESS. Đo thật
       ngày 2026-09-05: lượt chạy ấy đọc y hệt "14/14 xanh". kiem-sandbox.sh
       chạy chúng trong container và ĐẾM số ca, nên 0 ca là ĐỎ.

  Nhật ký worker:  docker logs -f oj-worker
  Dừng:            ./scripts/trien-khai-mac.sh --xoa
TIEP


