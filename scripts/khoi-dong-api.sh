#!/usr/bin/env bash
# =============================================================================
# oj-api trên host Mac, do launchd (dev.oj.api) giữ sống.
#
#   ./scripts/khoi-dong-api.sh cai                 # cài / cập nhật job — một lần, từ gốc repo
#   ./scripts/khoi-dong-api.sh doi-jar <jar>       # DEPLOY: trỏ sang jar mới, lùi nếu không lên
#   ./scripts/khoi-dong-api.sh go                  # gỡ job (API dừng hẳn)
#   ./scripts/khoi-dong-api.sh chay                # launchd gọi — không gõ tay
#
# Deploy đầy đủ, từ gốc repo trên main:
#   ./mvnw -B -q -DskipTests package -pl oj-api -am
#   cp oj-api/target/oj-api-0.0.1-SNAPSHOT.jar ~/oj-release/oj-api-$(git rev-parse --short HEAD)-$(date +%Y%m%d).jar
#   ./scripts/khoi-dong-api.sh doi-jar ~/oj-release/oj-api-<sha>-<ngay>.jar
#
# ★ VÌ SAO FILE NÀY TỒN TẠI (2026-09-25)
# API từng chạy bằng `nohup java -jar … &`. Máy khởi động lại lúc 23:58 ngày 24/09: hạ tầng
# và cloudflared tự lên, API thì không — 502 suốt 15 giờ 40 phút. Cách khởi động lại thì chỉ
# nằm trong một script tạm ở /private/tmp, mất cùng lần khởi động ấy.
#
# ★ api.env LÀ BẢN CHÉP CỦA .env, VÀ ĐÓ LÀ CÓ CHỦ Ý
# TCC chặn tiến trình launchd đọc ~/Desktop (xem dev.oj.api.plist). `cai` và `doi-jar` chạy
# từ terminal — đọc được Desktop — nên chúng chép .env sang ~/oj-release/api.env (600) mỗi
# lần. Sửa .env xong mà chưa chạy lại một trong hai lệnh ấy thì API vẫn dùng giá trị CŨ.
#
# ★ ĐỪNG `kill` API ĐỂ DEPLOY NỮA: launchd dựng lại ngay bản cũ. Dùng doi-jar.
# =============================================================================
set -euo pipefail

PHAT_HANH=$HOME/oj-release
ENV_CHEP=$PHAT_HANH/api.env
JAR_HIEN_TAI=$PHAT_HANH/oj-api-hien-tai.jar      # symlink — launchd luôn chạy đúng tên này
LOG=$PHAT_HANH/api.log
NHAN=dev.oj.api
PLIST=$HOME/Library/LaunchAgents/$NHAN.plist
MIEN=gui/$(id -u)
JAVA=${OJ_JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}
TRANG_THAI=http://127.0.0.1:8080/api/v1/status
GOC=$(cd "$(dirname "$0")/.." && pwd)            # gốc repo khi gọi từ scripts/

loi() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }

# ---- launchd gọi --------------------------------------------------------------------------
chay() {
    # 78 = EX_CONFIG. launchd thử lại sau ThrottleInterval, và dòng này nằm trong api.log.
    [ -r "$ENV_CHEP" ] || { echo "Không đọc được $ENV_CHEP — chạy: ./scripts/khoi-dong-api.sh cai" >&2; exit 78; }
    [ -e "$JAR_HIEN_TAI" ] || { echo "Không có $JAR_HIEN_TAI — chạy: ./scripts/khoi-dong-api.sh doi-jar <jar>" >&2; exit 78; }
    set -a; . "$ENV_CHEP"; set +a
    export SPRING_PROFILES_ACTIVE=prod       # KHÔNG có trong .env — thiếu là mất chốt F-7
    echo "===== launchd khởi động $(readlink "$JAR_HIEN_TAI") lúc $(date '+%F %T') ====="
    exec "$JAVA" -jar "$JAR_HIEN_TAI"
}

# ---- tiện ích cho các lệnh gõ tay ------------------------------------------------------------
pid_launchd() { launchctl print "$MIEN/$NHAN" 2>/dev/null | awk '$1 == "pid" {print $3}'; }

chep_ra_ngoai() {
    [ -r "$GOC/.env" ] || loi "Không có $GOC/.env — chạy lệnh này từ gốc repo."
    mkdir -p "$PHAT_HANH/bin"
    ( umask 077; cp "$GOC/.env" "$ENV_CHEP.tam" && mv "$ENV_CHEP.tam" "$ENV_CHEP" )
    install -m 755 "$GOC/scripts/khoi-dong-api.sh" "$PHAT_HANH/bin/khoi-dong-api.sh"
    ok "api.env (600) và script đã chép sang $PHAT_HANH"
}

# Đợi dòng "Started" MỚI (sau dòng thứ $1 của log) và /status 200. Không tính bản cũ còn thở.
cho_len() {
    local tu=$1 i
    for i in $(seq 1 120); do
        if tail -n +"$((tu + 1))" "$LOG" 2>/dev/null | grep -q "Started OjApiApplication" \
                && curl -fsS -m 2 "$TRANG_THAI" >/dev/null 2>&1; then
            ok "API lên sau ${i}s — PID $(pid_launchd)"; return 0
        fi
        sleep 1
    done
    echo "  Dòng đáng chú ý trong log:" >&2
    # `Error:` viết thường là của chính JVM (jar hỏng, sai phiên bản Java) — không qua logback.
    # Đo 2026-09-25: thiếu nó thì lượt thử jar hỏng in ra một danh sách rỗng.
    tail -n +"$((tu + 1))" "$LOG" | grep -E "ERROR|Error:|Caused by|APPLICATION FAILED|Không đọc được" \
        | sort | uniq -c | sort -rn | head -8 >&2 || true
    return 1
}

# Bản chạy bằng nohup (trước 2026-09-25) giữ cổng 8080; nạp job khi nó còn sống thì launchd
# dựng mãi một JVM chết vì "Port 8080 was already in use".
dung_ban_chay_tay() {
    local p
    p=$(lsof -tiTCP:8080 -sTCP:LISTEN 2>/dev/null || true)
    [ -n "$p" ] && [ "$p" != "$(pid_launchd)" ] || return 0
    echo "  dừng API chạy tay (PID $p)…"
    kill "$p"
    for _ in $(seq 60); do kill -0 "$p" 2>/dev/null || { ok "đã dừng PID $p"; return 0; }; sleep 1; done
    loi "PID $p chưa thoát sau 60s — không nạp job."
}

# CLAUDE.md §4.5: không động vào kỳ thi đang diễn ra. API không trả lời thì không hỏi được —
# lúc ấy site đã sập sẵn, nên cho qua.
kiem_ky_thi() {
    local dang
    dang=$(curl -fsS -m 5 "http://127.0.0.1:8080/api/v1/contests?size=50" 2>/dev/null | python3 -c '
import sys, json, datetime as d
now = d.datetime.now(d.timezone.utc); p = lambda s: d.datetime.fromisoformat(s.replace("Z", "+00:00"))
print(" ".join(c.get("slug", "?") for c in json.load(sys.stdin).get("items", [])
               if p(c["startsAt"]) - d.timedelta(minutes=30) <= now < p(c["endsAt"])))' 2>/dev/null || true)
    [ -z "$dang" ] || loi "Kỳ thi đang chạy hoặc mở trong 30 phút: $dang. Deploy sau khi kết thúc."
}

sao_luu_truoc() {
    local truoc sau
    truoc=$(ls -t "$HOME"/oj-backup/ojdb_prod/gio/oj-*.dump 2>/dev/null | head -1)
    launchctl kickstart "$MIEN/dev.oj.sao-luu" 2>/dev/null || { echo "  ! không kích được dev.oj.sao-luu"; return 0; }
    for _ in $(seq 90); do
        sau=$(ls -t "$HOME"/oj-backup/ojdb_prod/gio/oj-*.dump 2>/dev/null | head -1)
        [ -n "$sau" ] && [ "$sau" != "$truoc" ] && { ok "sao lưu trước deploy: $(basename "$sau")"; return 0; }
        sleep 1
    done
    echo "  ! 90s chưa thấy bản sao lưu mới — kiểm ~/oj-backup/sao-luu.log"
}

# ---- lệnh -------------------------------------------------------------------------------
cai() {
    chep_ra_ngoai
    if [ ! -e "$JAR_HIEN_TAI" ]; then
        local jar
        jar=$(ls -t "$PHAT_HANH"/oj-api-*.jar 2>/dev/null | grep -v hien-tai | head -1)
        [ -n "$jar" ] || loi "Chưa có jar nào trong $PHAT_HANH."
        ln -sfn "$(basename "$jar")" "$JAR_HIEN_TAI"
        ok "oj-api-hien-tai.jar → $(basename "$jar")"
    fi
    sed "s|@HOME@|$HOME|g" "$GOC/infra/launchd/$NHAN.plist" > "$PLIST"
    plutil -lint "$PLIST" >/dev/null || loi "plist hỏng: $PLIST"
    launchctl bootout "$MIEN/$NHAN" 2>/dev/null || true
    dung_ban_chay_tay
    local tu; tu=$(wc -l < "$LOG" 2>/dev/null || echo 0)
    launchctl bootstrap "$MIEN" "$PLIST"
    cho_len "$tu" || loi "Job đã nạp nhưng API chưa lên — đọc $LOG"
}

doi_jar() {
    local jar=${1:-} cu tu
    [ -n "$jar" ] || loi "Dùng: $0 doi-jar ~/oj-release/oj-api-<sha>-<ngay>.jar"
    jar=$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")
    [ -f "$jar" ] || loi "Không có $jar"
    [ "$(dirname "$jar")" = "$PHAT_HANH" ] || loi "Jar phải nằm trong $PHAT_HANH (target/ bị ghi đè mỗi lần build)."
    [ -n "$(pid_launchd)" ] || loi "Job $NHAN chưa chạy — chạy '$0 cai' trước."
    kiem_ky_thi
    chep_ra_ngoai
    sao_luu_truoc
    cu=$(readlink "$JAR_HIEN_TAI")
    ln -sfn "$(basename "$jar")" "$JAR_HIEN_TAI"
    tu=$(wc -l < "$LOG")
    launchctl kickstart -k "$MIEN/$NHAN"
    if cho_len "$tu"; then
        ok "đang chạy $(basename "$jar") (bản trước: $cu)"
        return 0
    fi
    echo "✗ $(basename "$jar") không lên — LÙI về $cu" >&2
    ln -sfn "$cu" "$JAR_HIEN_TAI"
    tu=$(wc -l < "$LOG")
    launchctl kickstart -k "$MIEN/$NHAN"
    cho_len "$tu" && loi "đã lùi về $cu và nó chạy; bản mới cần xem lại." \
                  || loi "CẢ bản cũ cũng không lên — cần người xử lý ngay."
}

go() {
    launchctl bootout "$MIEN/$NHAN" 2>/dev/null && ok "đã gỡ job $NHAN — API dừng" || ok "job $NHAN không chạy"
    rm -f "$PLIST"
}

case "${1:-}" in
    chay)    chay ;;
    cai)     cai ;;
    doi-jar) doi_jar "${2:-}" ;;
    go)      go ;;
    *)       echo "Dùng: $0 {cai | doi-jar <jar> | go}" >&2; exit 2 ;;
esac
