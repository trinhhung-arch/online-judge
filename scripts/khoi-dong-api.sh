#!/usr/bin/env bash
set -euo pipefail

PHAT_HANH=$HOME/oj-release
ENV_CHEP=$PHAT_HANH/api.env
JAR_HIEN_TAI=$PHAT_HANH/oj-api-hien-tai.jar
LOG=$PHAT_HANH/api.log
NHAN=dev.oj.api
PLIST=$HOME/Library/LaunchAgents/$NHAN.plist
MIEN=gui/$(id -u)
JAVA=${OJ_JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}
TRANG_THAI=http://127.0.0.1:8080/api/v1/status
GOC=$(cd "$(dirname "$0")/.." && pwd)

loi() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }

chay() {
    [ -r "$ENV_CHEP" ] || { echo "Không đọc được $ENV_CHEP — chạy: ./scripts/khoi-dong-api.sh cai" >&2; exit 78; }
    [ -e "$JAR_HIEN_TAI" ] || { echo "Không có $JAR_HIEN_TAI — chạy: ./scripts/khoi-dong-api.sh doi-jar <jar>" >&2; exit 78; }
    set -a; . "$ENV_CHEP"; set +a
    export SPRING_PROFILES_ACTIVE=prod
    echo "===== launchd khởi động $(readlink "$JAR_HIEN_TAI") lúc $(date '+%F %T') ====="
    exec "$JAVA" -jar "$JAR_HIEN_TAI"
}

pid_launchd() { launchctl print "$MIEN/$NHAN" 2>/dev/null | awk '$1 == "pid" {print $3}'; }

chep_ra_ngoai() {
    [ -r "$GOC/.env" ] || loi "Không có $GOC/.env — chạy lệnh này từ gốc repo."
    mkdir -p "$PHAT_HANH/bin"
    ( umask 077; cp "$GOC/.env" "$ENV_CHEP.tam" && mv "$ENV_CHEP.tam" "$ENV_CHEP" )
    install -m 755 "$GOC/scripts/khoi-dong-api.sh" "$PHAT_HANH/bin/khoi-dong-api.sh"
    ok "api.env (600) và script đã chép sang $PHAT_HANH"
}

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
    tail -n +"$((tu + 1))" "$LOG" | grep -E "ERROR|Error:|Caused by|APPLICATION FAILED|Không đọc được" \
        | sort | uniq -c | sort -rn | head -8 >&2 || true
    return 1
}

dung_ban_chay_tay() {
    local p
    p=$(lsof -tiTCP:8080 -sTCP:LISTEN 2>/dev/null || true)
    [ -n "$p" ] && [ "$p" != "$(pid_launchd)" ] || return 0
    echo "  dừng API chạy tay (PID $p)…"
    kill "$p"
    for _ in $(seq 60); do kill -0 "$p" 2>/dev/null || { ok "đã dừng PID $p"; return 0; }; sleep 1; done
    loi "PID $p chưa thoát sau 60s — không nạp job."
}

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
