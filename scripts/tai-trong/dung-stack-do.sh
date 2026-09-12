#!/usr/bin/env bash
# =============================================================================
# Dựng STACK ĐO chạy SONG SONG với prod trên cùng máy — load test có số thật mà không tắt site.
#
#   ./scripts/tai-trong/dung-stack-do.sh          dựng (chạy lại khi đã dựng thì chỉ báo trạng thái)
#   ./scripts/tai-trong/dung-stack-do.sh --xoa    gỡ sạch
#
#   rồi đo:  BASE=http://127.0.0.1:18080 ./scripts/tai-trong/chay.sh on-dinh
#
# ★ VÌ SAO CÓ FILE NÀY. Ngày 2026-09-11 lệnh `BASE=http://192.168.1.2:8080 chay.sh` nhắm thẳng API prod
#   — IP LAN của chính máy chủ. chay.sh giờ chặn điều đó bằng bắt tay API↔database; file này dựng đúng
#   thứ bắt tay đòi: một API trên database đo riêng, và một máy chấm isolate THẬT chấm cho nó.
#
# ★ CÁI GÌ DÙNG CHUNG VỚI PROD, CÁI GÌ TÁCH — và vì sao:
#   Postgres  CHUNG container oj-postgres, database ojdb. Postgres là một phần của hệ thống được đo;
#             một instance riêng sẽ cho con số của một máy chủ khác.
#   MinIO     CHUNG, chỉ đọc testdata (content-addressed; đề A-PLUS-B của dev-seed có sẵn ở đó).
#   Redis     TÁCH (oj-do-redis): kênh pub/sub và bảng xếp hạng đánh khoá theo id, mà id của ojdb và
#             ojdb_prod trùng nhau — dùng chung là SSE của người dùng prod nhận sự kiện của bài đo.
#   RabbitMQ  TÁCH (oj-do-rabbitmq): chung judge.live là worker prod nhặt bài đo rồi claim vào API prod.
#   API đo    java -jar, cổng 18080/18081, profile dev (ojdb mang lịch sử dev-seed), bí mật RIÊNG.
#   Worker đo container oj-worker-do qua trien-khai-mac.sh: isolate thật, tên mac-m1max-host để phép đo
#             máy được ghi, tmpfs 4g (VM ~15GB, worker prod đã giữ 8g).
#   Redis/RabbitMQ chỉ mở trên 127.0.0.1: container vẫn gọi được qua host.docker.internal (đã thử).
#
# ★ env -i CHO API VÀ WORKER ĐO. Chạy script từ một terminal đã `source .env` của prod thì tiến trình
#   con thừa hưởng OJ_DB_MIGRATOR_USER=oj_migrator, khoá Turnstile, HSTS... và Flyway trên ojdb chạy sai
#   role. Tiến trình đo chỉ nhận đúng các biến khai báo dưới đây.
#
# ★ STACK ĐO TÁCH API/DB/REDIS/RABBITMQ — NHƯNG DÙNG CHUNG CPU VỚI PROD, và điều đó CHẠM TỚI PROD.
#   Worker prod (oj-worker) tự đo máy mỗi 15 phút. Trong lúc tải đo chạy, nó đo NHẦM máy chậm hơn.
#   Đo 2026-09-11 (on-dinh 30 phút, 2 bài/s): host_factor của PROD 0.993 → 1.052 → 1.052 → 0.998 khi
#   tải dừng, trong khi thời gian chấm thật phẳng suốt 30 phút (919–950ms p50) — tức là tranh CPU,
#   không phải máy chậm đi. host_factor quy đổi giới hạn thời gian (CLAUDE.md, từ vựng), nên suốt lượt
#   đo mọi bài nộp prod được chấm với giới hạn lệch ~5%: bất công với người nộp trước và sau.
#   → `docker pause oj-worker` TRƯỚC khi đo (bài prod xếp hàng, không mất), `docker unpause oj-worker`
#     sau. Pause cũng dừng luôn phép đo máy của nó. KHÔNG BAO GIỜ đo trong lúc có kỳ thi.
#
# ★ GIỚI HẠN CÒN LẠI: k6 chạy trên chính máy này — từ ~400 người ảo số bi quan hơn sự thật.
#
# Log, PID, bí mật: ~/oj-stack-do/ — ngoài repo, bí mật không bao giờ vào git.
# =============================================================================
set -uo pipefail
# ★ KHÔNG `| grep -q` trong pipeline ở file này: với `set -o pipefail`, grep -q thoát ngay khi khớp,
#   lệnh bên trái ăn SIGPIPE (mã 141) và cả pipeline bị coi là HỎNG dù đã khớp. Đo 2026-09-11:
#   dung-stack-do.sh báo "RabbitMQ không lên sau 60s" với một RabbitMQ sẵn sàng sau 6s.
#   Dùng `grep ... >/dev/null` — grep đọc hết đầu vào, không đóng pipe sớm.
GOC=$(cd "$(dirname "$0")/../.." && pwd)
NHA=${OJ_STACK_DO_DIR:-$HOME/oj-stack-do}
JAR="$GOC/oj-api/target/oj-api-0.0.1-SNAPSHOT.jar"
CONG_API=18080 CONG_QT=18081 CONG_REDIS=16379 CONG_RABBIT=25672
loi() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }
pid_api() { [ -f "$NHA/api.pid" ] && ps -p "$(cat "$NHA/api.pid")" >/dev/null 2>&1 && cat "$NHA/api.pid"; }
trang_thai() { curl -fsS --max-time 5 "http://127.0.0.1:$CONG_API/api/v1/status" 2>/dev/null; }

if [ "${1:-}" = --xoa ]; then
    # Tên miền đo đóng TRƯỚC: không để một tên miền công khai trỏ vào một API đang tắt dở.
    [ -f "$NHA/cloudflared-do.pid" ] && "$GOC/scripts/tai-trong/tunnel-do.sh" --xoa | sed 's/^ */  /'
    p=$(pid_api) && { kill "$p"; echo "  tắt API đo (PID $p)"; }
    TEN=oj-worker-do "$GOC/scripts/trien-khai-mac.sh" --xoa
    docker rm -f oj-do-redis oj-do-rabbitmq >/dev/null 2>&1 && echo "  xoá oj-do-redis, oj-do-rabbitmq"
    rm -f "$NHA/api.pid"
    ok "Đã gỡ stack đo. Dữ liệu đo còn trong ojdb — dọn:"
    echo "    docker exec -i oj-postgres psql -U ojuser -d ojdb -v xac_nhan_db=ojdb < $GOC/scripts/tai-trong/don-dep.sql"
    exit 0
fi

if p=$(pid_api); then
    ok "Stack đo ĐÃ chạy: API PID $p · $(trang_thai || echo 'API không trả lời')"
    echo "  Đo:  BASE=http://127.0.0.1:$CONG_API $GOC/scripts/tai-trong/chay.sh on-dinh   ·   Gỡ: $0 --xoa"
    exit 0
fi

echo "── Tiền kiểm ──"
[ -f "$JAR" ] || loi "Chưa có $JAR. Build với API prod ĐÃ TẮT — prod chạy từ chính file này."
docker ps --format '{{.Names}}' | grep -x oj-postgres >/dev/null || loi "Container oj-postgres không chạy."
docker exec -i oj-postgres psql -U ojuser -d ojdb -tAc "SELECT 1 FROM problems WHERE code = 'A-PLUS-B'" </dev/null \
    | grep 1 >/dev/null || loi "Database ojdb không có đề A-PLUS-B (dev-seed)."
for c in $CONG_API $CONG_QT $CONG_REDIS $CONG_RABBIT; do
    lsof -nP -iTCP:$c -sTCP:LISTEN >/dev/null 2>&1 && loi "Cổng $c đang bận — stack đo cũ chưa gỡ? Chạy: $0 --xoa"
done
ok "jar, oj-postgres/ojdb, đề A-PLUS-B, 4 cổng trống"

mkdir -p "$NHA" && chmod 700 "$NHA"
if [ ! -f "$NHA/bi-mat.env" ]; then
    ( umask 077; { echo "OJ_JWT_SECRET=$(openssl rand -base64 48)"; echo "OJ_TOTP_KEY=$(openssl rand -base64 48)"
                   echo "OJ_INTERNAL_SHARED_SECRET=$(openssl rand -hex 32)"; } > "$NHA/bi-mat.env" )
fi
. "$NHA/bi-mat.env"

echo; echo "── Redis + RabbitMQ đo ──"
docker run -d --name oj-do-redis -p 127.0.0.1:$CONG_REDIS:6379 redis:7-alpine >/dev/null || loi "Không dựng được oj-do-redis."
docker run -d --name oj-do-rabbitmq -p 127.0.0.1:$CONG_RABBIT:5672 \
    -e RABBITMQ_DEFAULT_USER=ojuser -e RABBITMQ_DEFAULT_PASS=ojpass rabbitmq:3.13-management-alpine >/dev/null \
    || loi "Không dựng được oj-do-rabbitmq."
for _ in $(seq 60); do docker logs oj-do-rabbitmq 2>&1 | grep 'Server startup complete' >/dev/null && break; sleep 1; done
docker logs oj-do-rabbitmq 2>&1 | grep 'Server startup complete' >/dev/null || loi "RabbitMQ đo không lên sau 60s: docker logs oj-do-rabbitmq"
ok "oj-do-redis :$CONG_REDIS · oj-do-rabbitmq :$CONG_RABBIT (chỉ 127.0.0.1)"

echo; echo "── API đo ──"
env -i PATH="$PATH" HOME="$HOME" \
    OJ_JWT_SECRET="$OJ_JWT_SECRET" OJ_TOTP_KEY="$OJ_TOTP_KEY" OJ_INTERNAL_SHARED_SECRET="$OJ_INTERNAL_SHARED_SECRET" \
    SPRING_PROFILES_ACTIVE=dev SERVER_PORT=$CONG_API MANAGEMENT_SERVER_PORT=$CONG_QT \
    OJ_DB_URL=jdbc:postgresql://127.0.0.1:5432/ojdb OJ_DB_APP_USER=ojuser OJ_DB_APP_PASSWORD=ojpass \
    SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=$CONG_REDIS \
    OJ_RABBIT_ENABLED=true OJ_RABBIT_HOST=127.0.0.1 OJ_RABBIT_PORT=$CONG_RABBIT OJ_RABBIT_USER=ojuser OJ_RABBIT_PASSWORD=ojpass \
    OJ_MINIO_ENDPOINT=http://127.0.0.1:9000 OJ_MINIO_ACCESS_KEY=ojminio OJ_MINIO_SECRET_KEY=ojminio123 \
    nohup java -jar "$JAR" > "$NHA/api.log" 2>&1 &
echo $! > "$NHA/api.pid"
ma=$(curl -s -o /dev/null -w '%{http_code}' --retry 90 --retry-delay 1 --retry-connrefused --retry-all-errors \
    --max-time 120 "http://127.0.0.1:$CONG_API/api/v1/status")
[ "$ma" = 200 ] || { tail -20 "$NHA/api.log" >&2; loi "API đo không lên (HTTP $ma). Log: $NHA/api.log"; }
ok "API đo PID $(cat "$NHA/api.pid") · http://127.0.0.1:$CONG_API · database ojdb"

echo; echo "── Worker đo (isolate thật) ──"
env -i PATH="$PATH" HOME="$HOME" TEN=oj-worker-do OJ_BOX_TMPFS=4g \
    OJ_INTERNAL_SHARED_SECRET="$OJ_INTERNAL_SHARED_SECRET" OJ_API_BASE_URL="http://host.docker.internal:$CONG_API" \
    OJ_RABBIT_HOST=host.docker.internal OJ_RABBIT_PORT=$CONG_RABBIT OJ_WORKER_HOST_NAME=mac-m1max-host \
    OJ_HOST_REFERENCE_CPU_MS=461 "$GOC/scripts/trien-khai-mac.sh" --khong-build 2>&1 | grep -E '^(✓|✗|  !|──)' \
    | grep -v 'Hai việc BẮT BUỘC'
[ "$(docker inspect -f '{{.State.Running}}' oj-worker-do 2>/dev/null)" = true ] || loi "Worker đo không chạy: docker logs oj-worker-do"

# Phép đo máy lúc worker khởi động là thứ ghi judge_hosts.last_seen_at; không có nó, chay.sh dừng.
for _ in $(seq 60); do
    song=$(trang_thai | python3 -c 'import sys, json; print(json.load(sys.stdin)["mayChamSong"])' 2>/dev/null)
    [ "${song:-0}" -ge 1 ] && break; sleep 1
done
[ "${song:-0}" -ge 1 ] || loi "mayChamSong vẫn 0 sau 60s — worker đo chưa gửi được phép đo máy: docker logs oj-worker-do"
ok "mayChamSong = $song"

cat <<TIEP

✓ STACK ĐO SẴN SÀNG — tách API, database, Redis, RabbitMQ khỏi prod. KHÔNG tách được CPU.

  Đo (mỗi lệnh một kịch bản):
    BASE=http://127.0.0.1:$CONG_API $GOC/scripts/tai-trong/chay.sh on-dinh
    BASE=http://127.0.0.1:$CONG_API $GOC/scripts/tai-trong/chay.sh dot-bien
    BASE=http://127.0.0.1:$CONG_API CAC_MUC="100 200" $GOC/scripts/tai-trong/chay.sh quet

  Gỡ:          $0 --xoa
TIEP
# Nói to, không để trong chú thích: đo trên cùng CPU mà worker prod còn chạy là lệch host_factor của prod.
if [ "$(docker inspect -f '{{.State.Status}}' oj-worker 2>/dev/null)" = running ]; then
    echo
    echo "⚠ Worker PROD (oj-worker) đang chạy. Trong lúc đo nó sẽ đo nhầm máy chậm hơn (~5% ở 2 bài/s,"
    echo "  đo 2026-09-11) và nới giới hạn thời gian cho bài nộp prod. Pause nó trước khi đo:"
    echo "    docker pause oj-worker          # xong đo:  docker unpause oj-worker"
fi
