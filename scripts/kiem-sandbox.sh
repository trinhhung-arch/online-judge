#!/usr/bin/env bash
set -euo pipefail

ANH_CHAY=${ANH:-oj-worker:arm64}
ANH_TEST=${ANH_TEST:-oj-worker-test:arm64}
NEN_TANG=${NEN_TANG:-linux/arm64}
SO_CA_MONG_DOI=${SO_CA_MONG_DOI:-14}

GOC=$(cd "$(dirname "$0")/.." && pwd)
IT=${IT:-SandboxAttackIT}

khong_build=0
for a in "$@"; do case "$a" in
    --khong-build) khong_build=1 ;;
    *) echo "Tham số lạ: $a" >&2; exit 2 ;;
esac; done

loi() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }

command -v docker >/dev/null || loi "Chưa có Docker."
docker image inspect "$ANH_CHAY" >/dev/null 2>&1 \
    || loi "Chưa có ảnh chạy $ANH_CHAY. Dựng nó trước: ./scripts/trien-khai-mac.sh"

if [ "$khong_build" -eq 0 ]; then
    echo "── Build $ANH_TEST (từ $ANH_CHAY + Maven/JDK) ──"
    docker buildx build --platform "$NEN_TANG" \
        -f "$GOC/infra/isolate/Dockerfile.test" \
        --build-arg "ANH=$ANH_CHAY" \
        -t "$ANH_TEST" --load "$GOC"
    ok "đã build $ANH_TEST"
fi

KICH_BAN='set -e
mount -o remount,rw /sys/fs/cgroup
mkdir -p /sys/fs/cgroup/init /sys/fs/cgroup/boxes /run/isolate
for p in $(cat /sys/fs/cgroup/cgroup.procs); do echo $p > /sys/fs/cgroup/init/cgroup.procs 2>/dev/null || true; done
for c in cpuset cpu memory pids; do
  echo +$c > /sys/fs/cgroup/cgroup.subtree_control 2>/dev/null || true
  echo +$c > /sys/fs/cgroup/boxes/cgroup.subtree_control 2>/dev/null || true
done
grep -q memory /sys/fs/cgroup/boxes/cgroup.subtree_control || { echo "khong bat duoc controller memory cho /sys/fs/cgroup/boxes" >&2; exit 1; }
echo /sys/fs/cgroup/boxes > /run/isolate/cgroup
# Bao cao cu tu luot truoc doc y het bao cao that. Xoa truoc khi chay.
rm -rf /work/oj-worker/target/failsafe-reports
mvn -B -pl oj-worker -am verify -Dit.test="$OJ_IT"
ma=$?
# Tra lai quyen so huu target/ cho nguoi dung host — Maven vua ghi bang root tren bind mount.
chown -R "$OJ_HOST_UID:$OJ_HOST_GID" /work/oj-worker/target /work/oj-contract/target 2>/dev/null || true
exit $ma'

echo
echo "── Chạy $IT trong container ──"
NHAT_KY=$(mktemp -t oj-sandbox-test)
trap 'rm -f "$NHAT_KY"' EXIT

docker run --rm --name oj-sandbox-test \
    --user root --entrypoint /bin/sh \
    --cgroupns=private \
    --cap-add SYS_ADMIN --cap-add SYS_RESOURCE --cap-add SYS_CHROOT --cap-add NET_ADMIN \
    --tmpfs "/var/local/lib/isolate:size=${OJ_BOX_TMPFS:-2g},mode=755" \
    -e OJ_HOST_UID="$(id -u)" -e OJ_HOST_GID="$(id -g)" \
    -e OJ_IT="$IT" \
    -v "$GOC":/work \
    -v "$HOME/.m2":/root/.m2 \
    "$ANH_TEST" -c "$KICH_BAN" 2>&1 | tee "$NHAT_KY" || true

echo
echo "── Kết luận ──"

THU_MUC="$GOC/oj-worker/target/failsafe-reports"
lay() { printf '%s' "$2" | sed -nE "s/.*[[:space:]]$1=\"([0-9]+)\".*/\1/p"; }
tong_ca=0

for ten in $(printf '%s' "$IT" | tr ',' ' '); do
    BAO_CAO=$(ls "$THU_MUC"/TEST-*."$ten".xml 2>/dev/null | head -1 || true)
    if [ -z "$BAO_CAO" ]; then
        echo "  (không có TEST-*.$ten.xml trong $THU_MUC)" >&2
        loi "Failsafe chưa chạy được ca nào của $ten — build chết TRƯỚC nó.
     Gần như luôn là một ca unit test đỏ ở bước surefire. Tìm trong nhật ký phía trên:
       [ERROR] Tests run: ... in dev.oj.worker....
     Sửa ca đó rồi chạy lại. $ten CHƯA chứng minh gì trong lượt này."
    fi

    dong=$(grep -m1 '<testsuite ' "$BAO_CAO")
    chay=$(lay tests "$dong")
    hong=$(lay failures "$dong")
    loi_ca=$(lay errors "$dong")
    bo=$(lay skipped "$dong")
    [ -n "$chay$hong$loi_ca$bo" ] || loi "Không đọc được số liệu trong $BAO_CAO."

    echo "  $ten — chạy $chay · hỏng $hong · lỗi $loi_ca · bỏ $bo"

    [ "$chay" -ge 1 ] || loi "$ten chạy 0 ca. Một lớp bị huỷ và một lớp xanh có cùng mã thoát."
    [ "$hong" -eq 0 ] && [ "$loi_ca" -eq 0 ] \
        || loi "$ten: $hong ca hỏng, $loi_ca ca lỗi. CHƯA đạt — đọc nhật ký phía trên."
    [ "$bo" -eq 0 ] || loi "$ten: $bo ca bị bỏ. Một ca bị bỏ là một ca chưa chứng minh gì."

    if [ "$ten" = SandboxAttackIT ] && [ "$chay" -ne "$SO_CA_MONG_DOI" ]; then
        loi "Chạy $chay/$SO_CA_MONG_DOI ca tấn công — KHÔNG đủ.
     Số ca chạy khác $SO_CA_MONG_DOI nghĩa là có ca bị huỷ hoặc bị skip, KHÔNG phải là
     sandbox an toàn. Ca bị huỷ và ca xanh nhìn giống nhau ở mã thoát của Maven; đó
     chính là lý do script này đếm."
    fi

    tong_ca=$((tong_ca + chay))
done

ok "$tong_ca ca xanh trên $ANH_CHAY  ($IT)."
