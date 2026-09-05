#!/usr/bin/env bash
# =============================================================================
# Triển khai oj-worker lên máy chấm Mac M1 Max (arm64).
#
#   OJ_INTERNAL_SHARED_SECRET=... ./scripts/trien-khai-mac.sh
#   ./scripts/trien-khai-mac.sh --chi-kiem      # chỉ kiểm tiền đề, không build
#   ./scripts/trien-khai-mac.sh --khong-build   # dùng lại ảnh đã có
#   ./scripts/trien-khai-mac.sh --xoa           # dừng và xoá container
#
# ★ SCRIPT NÀY CHỈ TRIỂN KHAI WORKER.
# Postgres · Redis · RabbitMQ · MinIO · oj-api chạy thẳng trên Mac bằng
# `docker compose up -d` và `./mvnw -pl oj-api spring-boot:run` như README. Bốn
# ảnh hạ tầng đều có bản arm64 nên không cần gì đặc biệt. Chỉ WORKER là khó, vì
# chỉ nó cần `isolate`.
#
# ★ VÌ SAO WORKER PHẢI NẰM TRONG CONTAINER TRÊN MAC
# `isolate` là chương trình setuid root nói chuyện thẳng với cgroup v2 và
# namespace của kernel Linux. macOS không có cả hai. Xem scripts/build-isolate.sh
# — nó thoát ngay nếu `uname -s` khác Linux.
#
# ★ MỘT THỨ SCRIPT NÀY THÊM SO VỚI Dockerfile
# Ảnh đặt ENTRYPOINT là `java -jar` và USER là `ojworker`. Nhưng header của
# infra/isolate/Dockerfile nói `isolate-cg-keeper` phải chạy TRƯỚC worker, mà
# keeper thì cần root để tạo cgroup. Hai điều đó không cùng lúc đúng được với
# ENTRYPOINT mặc định, nên script ghi đè entrypoint: vào bằng root, dựng keeper,
# đợi /run/isolate/cgroup xuất hiện, rồi HẠ QUYỀN xuống ojworker mới chạy JVM.
#
# Đó là chỗ duy nhất script này đi chệch khỏi ảnh, và nó đi chệch có chủ ý:
# JVM chạy root là phá bất biến sandbox #7.
#
# ⚠️ CHƯA CHẠY THỬ TRÊN MAC THẬT. Viết từ header của Dockerfile và cấu hình của
#    worker; cú pháp bash đã kiểm, phần hành vi thì chưa ai chạy. Đọc rồi hãy tin.
# =============================================================================
set -euo pipefail

ANH=${ANH:-oj-worker:arm64}
TEN=${TEN:-oj-worker}
NEN_TANG=${NEN_TANG:-linux/arm64}
GOC=$(cd "$(dirname "$0")/.." && pwd)

# Trong container, `localhost` là chính container. Muốn với tới API và RabbitMQ
# đang chạy trên macOS thì phải đi qua host-gateway.
API=${OJ_API_BASE_URL:-http://host.docker.internal:8080}
RABBIT=${OJ_RABBIT_HOST:-host.docker.internal}
SLOTS=${OJ_WORKER_SLOTS:-6}          # ADR 008 — 6 chứ không 9, vì nhiệt
HOST_NAME=${OJ_WORKER_HOST_NAME:-mac-m1max-host}   # khớp judge_hosts.name
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

# ─────────────────────────────────────────────────────────────────────────────
echo "── Kiểm tiền đề ──"

[ "$(uname -s)" = "Darwin" ] || luu_y "Không phải macOS ($(uname -s)) — script vẫn chạy được, nhưng nó viết cho Mac."

command -v docker >/dev/null || loi "Chưa có Docker. Cài Docker Desktop hoặc OrbStack."
docker buildx version >/dev/null 2>&1 || loi "Chưa có docker buildx — cần nó để build đúng $NEN_TANG."
docker info >/dev/null 2>&1 || loi "Docker chưa chạy."
ok "docker + buildx"

# Kiến trúc VM. Build arm64 trên VM amd64 sẽ đi qua QEMU: chạy được, nhưng mọi
# con số thời gian vô nghĩa — mà đo thời gian đúng là lý do máy chấm chuẩn tồn tại.
arch_vm=$(docker info --format '{{.Architecture}}' 2>/dev/null || echo '?')
case "$arch_vm" in
    aarch64|arm64) ok "VM Docker là arm64" ;;
    *) luu_y "VM Docker là '$arch_vm', không phải arm64. Ảnh $NEN_TANG sẽ chạy qua giả lập"
       luu_y "và MỌI con số thời gian đo được sẽ sai. Không dùng máy này làm máy chấm chuẩn." ;;
esac

# RAM của VM: --tmpfs $TMPFS nằm trong đó, cộng 6 slot đang biên dịch C++.
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

if curl -fsS --max-time 3 "${API/host.docker.internal/localhost}/api/v1/status" >/dev/null 2>&1; then
    ok "API trả lời tại ${API/host.docker.internal/localhost}"
else
    luu_y "Chưa gọi được API. Worker khởi động trước API là chuyện thường (missing-queues-fatal: false),"
    luu_y "nhưng nếu API không bao giờ lên thì worker sẽ chờ mãi mà không báo lỗi to."
fi

[ "$chi_kiem" -eq 1 ] && { echo; echo "Chỉ kiểm tiền đề — dừng ở đây."; exit 0; }

# ─────────────────────────────────────────────────────────────────────────────
if [ "$khong_build" -eq 0 ]; then
    echo
    echo "── Build $ANH cho $NEN_TANG ──"
    # --load: nạp thẳng vào daemon để `docker run` thấy. isolate được build LẠI
    # từ nguồn trong stage 1 cho đúng kiến trúc — không bao giờ copy binary.
    docker buildx build \
        --platform "$NEN_TANG" \
        -f "$GOC/infra/isolate/Dockerfile" \
        -t "$ANH" --load "$GOC"
    ok "đã build $ANH"
fi

# ─────────────────────────────────────────────────────────────────────────────
echo
echo "── Chạy $TEN ──"
docker rm -f "$TEN" >/dev/null 2>&1 || true

# Vào bằng root để dựng keeper, rồi hạ quyền. Xem chú thích đầu file.
# ★ REMOUNT /sys/fs/cgroup THÀNH GHI ĐƯỢC — dòng đầu tiên, trước cả keeper.
#
# Docker mount /sys/fs/cgroup READ-ONLY vào container, kể cả với --cgroupns=private.
# `isolate --init` phải tạo một control group cho mỗi box, nên nó chết ngay ở box đầu tiên:
#     Failed to create control group /sys/fs/cgroup//box-1: Read-only file system
# Triệu chứng phía trên là MỌI bài nộp trả IE trong ~370ms — nhanh hơn cả ngân sách biên
# dịch, vì nó hỏng trước khi kịp biên dịch. Đo trên host Mac (OrbStack) ngày 2026-09-05.
#
# Container đã có SYS_ADMIN nên remount được, và đây không phải nới thêm quyền: nó dùng
# đúng cái quyền đã cấp có chủ ý ở --cap-add, để isolate siết được mã người dùng bên trong.
#
# KHÔNG ghi vào cgroup.subtree_control ở đây: cgroup gốc của container đang chứa tiến trình,
# nên cgroup v2 trả EBUSY (luật 'no internal processes'). isolate tự lo phần đó trong --init.
# ★ BA CGROUP, MỖI CÁI MỘT VAI. Đây là hình dạng bắt buộc của cgroup v2, không phải sở thích:
#
#   /sys/fs/cgroup/         controller đã bật, KHÔNG chứa tiến trình
#   ├── init/               chứa shell + JVM          ← mọi tiến trình ở đây
#   └── boxes/              controller đã bật, KHÔNG tiến trình ← nhà của box
#       └── box-N/          isolate tạo, có memory.max
#
# Luật 'no internal processes' của cgroup v2: không bật được controller trên một cgroup còn
# chứa tiến trình. Nên cgroup làm nhà cho box BẮT BUỘC phải rỗng tiến trình — và đó là lý do
# không dùng được isolate-cg-keeper ở đây: keeper lấy cgroup của CHÍNH NÓ làm cg_root, mà
# cgroup ấy đương nhiên chứa nó. Đo được lần lượt trên host Mac ngày 2026-09-05:
#     keeper ở gốc  -> Cannot write /sys/fs/cgroup//box-1/memory.max: No such file or directory
#     keeper ở /init -> Cannot write /sys/fs/cgroup/init/box-91/memory.max: ...
#
# Việc duy nhất keeper làm là ghi đường dẫn cgroup vào /run/isolate/cgroup (nơi `cg_root =
# auto:` trỏ tới). Ta tự ghi được. Không có systemd thì cũng không ai dọn cgroup ấy đi, nên
# phần 'keeper' của nó không cần thiết.
#
# Dòng `grep -q memory` là cái chốt: thà container không lên còn hơn lên mà --cg-mem không
# ép gì — một sandbox không giới hạn RAM trông y hệt một sandbox bình thường cho tới lúc
# có người khai thác nó.
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
    --user root \
    --entrypoint /bin/sh \
    --cgroupns=private \
    --security-opt seccomp=unconfined \
    --security-opt apparmor=unconfined \
    --cap-add SYS_ADMIN --cap-add SYS_RESOURCE --cap-add SYS_CHROOT --cap-add NET_ADMIN \
    --tmpfs "/var/local/lib/isolate:size=$TMPFS,mode=755" \
    --add-host host.docker.internal:host-gateway \
    -e OJ_INTERNAL_SHARED_SECRET="$OJ_INTERNAL_SHARED_SECRET" \
    -e OJ_API_BASE_URL="$API" \
    -e OJ_RABBIT_HOST="$RABBIT" \
    -e OJ_RABBIT_PORT="${OJ_RABBIT_PORT:-5672}" \
    -e OJ_RABBIT_USER="${OJ_RABBIT_USER:-ojuser}" \
    -e OJ_RABBIT_PASSWORD="${OJ_RABBIT_PASSWORD:-ojpass}" \
    -e OJ_WORKER_HOST_NAME="$HOST_NAME" \
    -e OJ_WORKER_ARCH=arm64 \
    -e OJ_WORKER_SLOTS="$SLOTS" \
    "$ANH" -c "$KHOI_DONG" >/dev/null
ok "container đã chạy"

# ─────────────────────────────────────────────────────────────────────────────
echo
echo "── Kiểm bên trong container ──"
sleep 3
kiem() {
    if docker exec "$TEN" sh -c "$2" >/dev/null 2>&1; then ok "$1"; else luu_y "$1 — KHÔNG đạt"; fi
}
kiem "cgroup v2 (unified hierarchy)" '[ "$(stat -fc %T /sys/fs/cgroup)" = cgroup2fs ]'
kiem "isolate chạy được"             '/usr/local/bin/isolate --version'
# ★ Ca kiểm trên KHÔNG đủ, và đã có lần báo xanh trên một cài đặt hỏng: `--version` không
# đọc file config, nên nó vẫn chạy khi đường dẫn config nhúng trong binary trỏ sai chỗ
# (xem chú thích PREFIX/DESTDIR trong infra/isolate/Dockerfile). Ca dưới đây dựng một box
# THẬT rồi dọn — nó buộc isolate phải đọc được config VÀ mượn được cgroup. Box 90 nằm ngoài
# dải judge slot (0..slots-1) nên không giẫm lên bài đang chấm.
kiem "isolate dựng được box CÓ giới hạn RAM" '/usr/local/bin/isolate --cg -b 90 --init >/dev/null 2>&1 && [ -f /sys/fs/cgroup/boxes/box-90/memory.max ]; r=$?; /usr/local/bin/isolate --cg -b 90 --cleanup >/dev/null 2>&1; exit $r'
kiem "isolate là setuid root"        '[ -u /usr/local/bin/isolate ]'
kiem "/etc/subuid có dòng isolate"   'grep -q "^isolate:" /etc/subuid'
kiem "cgroup boxes có controller memory+pids" 'grep -q memory /sys/fs/cgroup/boxes/cgroup.subtree_control && grep -q pids /sys/fs/cgroup/boxes/cgroup.subtree_control'
# Không dùng pgrep/ps: `debian:bookworm-slim` KHÔNG có procps (đã đo). Một ca
# kiểm gọi lệnh không tồn tại sẽ báo hỏng oan, và báo hỏng oan còn tệ hơn không
# kiểm — nó dạy người đọc bỏ qua dòng cảnh báo.
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
     đổi giới hạn. Đọc con số đo được rồi đặt lại biến ấy.
     Bỏ qua bước này = mọi giới hạn thời gian vẫn đang tính theo máy WSL x86.

  2. CHẠY LẠI 14 TEST TẤN CÔNG SANDBOX.
     ./mvnw -pl oj-worker verify -Dit.test=SandboxAttackIT
     CLAUDE.md mục 6 đòi điều này mỗi khi đụng sandbox, và đổi kiến trúc máy
     CHÍNH LÀ đụng sandbox. Cái bẫy: khi nới/siết quyền container, vài ca chuyển
     từ "bị chặn" sang "không chạy được" — và hai kết quả đó nhìn giống hệt nhau
     trong log.

  Nhật ký worker:  docker logs -f oj-worker
  Dừng:            ./scripts/trien-khai-mac.sh --xoa
TIEP
