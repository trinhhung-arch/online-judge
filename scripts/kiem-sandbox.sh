#!/usr/bin/env bash
# =============================================================================
# Chạy 14 TEST TẤN CÔNG SANDBOX (nfrplan.md 4.1) trong container Linux có isolate.
#
#   ./scripts/kiem-sandbox.sh              # build ảnh test rồi chạy
#   ./scripts/kiem-sandbox.sh --khong-build
#
# CLAUDE.md mục 6 đòi chạy lại toàn bộ 14 ca mỗi khi đụng sandbox — và đổi kiến trúc máy
# chấm (WSL x86 -> container trên Mac arm64) CHÍNH LÀ đụng sandbox.
#
# ★ VÌ SAO KHÔNG GỌI THẲNG ./mvnw TRÊN MAC
# isolate cần cgroup v2 + namespace Linux. Trên macOS, WorkerFixtures.requireIsolate gọi
# Assumptions.abort() -> JUnit huỷ cả class -> "Tests run: 0" -> BUILD SUCCESS. Mã thoát 0
# ở đó KHÔNG có nghĩa là sandbox an toàn; nó có nghĩa là chưa ai thử. Script này vì thế
# ĐẾM số ca đã chạy, và chỉ báo xanh khi đủ 14.
#
# ⚠️ SCRIPT NÀY CHƯA ĐƯỢC CHẠY TRỌN VẸN MỘT LẦN NÀO. Cú pháp bash đã kiểm, ảnh đã build
#    được, nhưng lượt `docker run` có --cap-add SYS_ADMIN thì chưa ai chạy. Lần chạy đầu
#    hãy đọc nhật ký chứ đừng chỉ đọc dòng kết luận.
#
# ★ CÁI BẪY MÀ HEADER trien-khai-mac.sh ĐÃ CẢNH BÁO
# Khi nới/siết quyền container, một ca chuyển từ "bị chặn" sang "không chạy được" trông
# giống hệt nhau trong log. Đó là lý do ngưỡng là "đúng 14 ca CHẠY", không phải "không ca
# nào đỏ".
# =============================================================================
set -euo pipefail

ANH_CHAY=${ANH:-oj-worker:arm64}
ANH_TEST=${ANH_TEST:-oj-worker-test:arm64}
NEN_TANG=${NEN_TANG:-linux/arm64}
SO_CA_MONG_DOI=${SO_CA_MONG_DOI:-14}
GOC=$(cd "$(dirname "$0")/.." && pwd)

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

# Cùng bộ cờ và cùng cách dựng cgroup như trien-khai-mac.sh. Hai chỗ này phải khớp nhau:
# test một sandbox dựng khác cách với sandbox chạy thật thì không chứng minh gì.
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
mvn -B -pl oj-worker -am verify -Dit.test=SandboxAttackIT
ma=$?
# Tra lai quyen so huu target/ cho nguoi dung host — Maven vua ghi bang root tren bind mount.
chown -R "$OJ_HOST_UID:$OJ_HOST_GID" /work/oj-worker/target /work/oj-contract/target 2>/dev/null || true
exit $ma'

echo
echo "── Chạy $SO_CA_MONG_DOI test tấn công trong container ──"
NHAT_KY=$(mktemp -t oj-sandbox-test)
trap 'rm -f "$NHAT_KY"' EXIT

# `|| true`: mã thoát của Maven KHÔNG phải tiêu chí ở đây — xem header. Tiêu chí là số ca
# thực sự chạy, đọc từ dòng "Tests run:" của Failsafe bên dưới.
docker run --rm --name oj-sandbox-test \
    --user root --entrypoint /bin/sh \
    --cgroupns=private \
    --security-opt seccomp=unconfined \
    --security-opt apparmor=unconfined \
    --cap-add SYS_ADMIN --cap-add SYS_RESOURCE --cap-add SYS_CHROOT --cap-add NET_ADMIN \
    --tmpfs "/var/local/lib/isolate:size=${OJ_BOX_TMPFS:-2g},mode=755" \
    -e OJ_HOST_UID="$(id -u)" -e OJ_HOST_GID="$(id -g)" \
    -v "$GOC":/work \
    -v "$HOME/.m2":/root/.m2 \
    "$ANH_TEST" -c "$KICH_BAN" 2>&1 | tee "$NHAT_KY" || true

echo
echo "── Kết luận ──"

# ★ ĐỌC BÁO CÁO CỦA FAILSAFE, KHÔNG ĐỌC CONSOLE.
# Bản đầu bắt dòng "[INFO] Tests run: ..." cuối cùng trong nhật ký. Nó sai: surefire in
# một dòng như thế cho MỖI class unit test, mà khối tổng kết cuối lại mang tiền tố
# [ERROR] khi có ca đỏ. Đo thật ngày 2026-09-05: surefire chết ở CommandTemplateTest,
# failsafe không chạy ca nào, script vẫn in "đã chạy 4" — con số của WorkerArchitectureTest.
# Nó báo đỏ đúng, nhưng vì lý do sai, và một con số sai trong dòng kết luận thì lần sau
# sẽ dẫn người đọc đi sai đường.
#
# File XML của Failsafe không có chỗ cho nhầm lẫn ấy: nó chỉ tồn tại khi failsafe thật sự
# chạy, và thuộc tính tests= là số ca của ĐÚNG class này.
BAO_CAO="$GOC/oj-worker/target/failsafe-reports/TEST-dev.oj.worker.sandbox.SandboxAttackIT.xml"
if [ ! -f "$BAO_CAO" ]; then
    echo "  (không có $BAO_CAO)" >&2
    loi "Failsafe chưa chạy được ca nào — build chết TRƯỚC nó.
     Gần như luôn là một ca unit test đỏ ở bước surefire. Tìm trong nhật ký phía trên:
       [ERROR] Tests run: ... in dev.oj.worker....
     Sửa ca đó rồi chạy lại. Ca tấn công CHƯA chứng minh gì trong lượt này."
fi

dong=$(grep -m1 '<testsuite ' "$BAO_CAO")
lay() { printf '%s' "$dong" | sed -nE "s/.*[[:space:]]$1=\"([0-9]+)\".*/\1/p"; }
chay=$(lay tests)
hong=$(lay failures)
loi_ca=$(lay errors)
bo=$(lay skipped)
[ -n "$chay$hong$loi_ca$bo" ] || loi "Không đọc được số liệu trong $BAO_CAO."

echo "  đã chạy $chay · hỏng $hong · lỗi $loi_ca · bỏ $bo"

[ "$chay" -eq "$SO_CA_MONG_DOI" ] || loi "Chạy $chay/$SO_CA_MONG_DOI ca — KHÔNG đủ.
     Số ca chạy khác $SO_CA_MONG_DOI nghĩa là có ca bị huỷ hoặc bị skip, KHÔNG phải là
     sandbox an toàn. Ca bị huỷ và ca xanh nhìn giống nhau ở mã thoát của Maven; đó
     chính là lý do script này đếm."
[ "$hong" -eq 0 ] && [ "$loi_ca" -eq 0 ] || loi "$hong ca hỏng, $loi_ca ca lỗi. Sandbox CHƯA đạt — đọc nhật ký phía trên."
[ "$bo" -eq 0 ] || loi "$bo ca bị bỏ. Một ca bị bỏ là một ca chưa chứng minh gì."

ok "$SO_CA_MONG_DOI/$SO_CA_MONG_DOI test tấn công xanh trên $ANH_CHAY."
