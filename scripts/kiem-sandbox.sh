#!/usr/bin/env bash
# =============================================================================
# Chạy 14 TEST TẤN CÔNG SANDBOX (nfrplan.md 4.1) trong container Linux có isolate.
#
#   ./scripts/kiem-sandbox.sh              # build ảnh test rồi chạy
#   ./scripts/kiem-sandbox.sh --khong-build
#   IT=SandboxAttackIT,IsolateJudgeRunnerIT ./scripts/kiem-sandbox.sh   # thêm đường chấm thật
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
# ✔ ĐÃ CHẠY TRỌN VẸN: 2026-09-15 trên host Mac M1 Max (OrbStack), ảnh oj-worker:arm64 dựng
#   lại cùng ngày — 14/14 ca chạy, 0 hỏng, 0 bỏ, hết 5,9 giây. Lượt `docker run` có
#   --cap-add SYS_ADMIN chạy được, cgroup ba tầng dựng đúng. Dòng cảnh báo cũ ở đây nói
#   script chưa từng chạy trọn vẹn; nó đã cũ và được thay bằng chính con số này.
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

# ★ Lớp IT được chọn. Mặc định đúng bằng hành vi cũ — chỉ bộ tấn công.
#
# Vì sao mở ra: `sandbox-attack.yml` trên CI chạy `verify` KHÔNG kèm -Dit.test, nên nó chạy
# cả IsolateJudgeRunnerIT ("đường chấm thật: C++ -> verdict") và HostBenchmarkIT. Script này
# thì ghim cứng một lớp, nên trên máy chấm thật có một câu chưa bao giờ được hỏi: ảnh vừa
# dựng CHẶN được tấn công, nhưng nó CHẤM được một bài bình thường không?
#
# Tên đơn, phân tách bằng dấu phẩy — đúng cú pháp -Dit.test của Failsafe:
#   IT=SandboxAttackIT,IsolateJudgeRunnerIT
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

# Cùng bộ cờ và cùng cách dựng cgroup như trien-khai-mac.sh (seccomp MẶC ĐỊNH từ 2026-09-24 —
# lý do và phép đo ở chú thích ngay trên `docker run` trong trien-khai-mac.sh). Hai chỗ này phải khớp nhau:
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
mvn -B -pl oj-worker -am verify -Dit.test="$OJ_IT"
ma=$?
# Tra lai quyen so huu target/ cho nguoi dung host — Maven vua ghi bang root tren bind mount.
chown -R "$OJ_HOST_UID:$OJ_HOST_GID" /work/oj-worker/target /work/oj-contract/target 2>/dev/null || true
exit $ma'

echo
echo "── Chạy $IT trong container ──"
NHAT_KY=$(mktemp -t oj-sandbox-test)
trap 'rm -f "$NHAT_KY"' EXIT

# `|| true`: mã thoát của Maven KHÔNG phải tiêu chí ở đây — xem header. Tiêu chí là số ca
# thực sự chạy, đọc từ dòng "Tests run:" của Failsafe bên dưới.
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
THU_MUC="$GOC/oj-worker/target/failsafe-reports"
lay() { printf '%s' "$2" | sed -nE "s/.*[[:space:]]$1=\"([0-9]+)\".*/\1/p"; }
tong_ca=0

# ★ MỖI lớp trong $IT phải để lại một báo cáo, và mọi con số trong đó phải sạch.
#
# Vòng lặp này là điều kiện để $IT mở ra được mà không làm rỗng ruột phép kiểm. Mã thoát
# của Maven bị bỏ qua có chủ ý (`|| true` phía trên), nên nếu chỉ đọc báo cáo của
# SandboxAttackIT thì thêm một lớp vào $IT là thêm một lớp chạy xong KHÔNG AI ĐỌC KẾT QUẢ —
# nó hỏng mà dòng cuối vẫn in màu xanh. Đó đúng là kiểu hỏng mà cả script này sinh ra để
# ngăn, nên nó không được phép xuất hiện ở chính đây.
#
# Tên file có dạng TEST-<gói>.<Lớp>.xml, mà -Dit.test nhận tên ĐƠN — nên tra bằng glob
# thay vì gõ cứng tên gói: thêm một lớp ở gói khác cũng không phải sửa dòng nào.
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

    # Chỉ bộ tấn công mới có con số cố định, và nó là con số của nfrplan 4.1 — 14, không
    # phải "bao nhiêu cũng được miễn xanh". Các lớp khác chỉ cần chạy thật và sạch.
    if [ "$ten" = SandboxAttackIT ] && [ "$chay" -ne "$SO_CA_MONG_DOI" ]; then
        loi "Chạy $chay/$SO_CA_MONG_DOI ca tấn công — KHÔNG đủ.
     Số ca chạy khác $SO_CA_MONG_DOI nghĩa là có ca bị huỷ hoặc bị skip, KHÔNG phải là
     sandbox an toàn. Ca bị huỷ và ca xanh nhìn giống nhau ở mã thoát của Maven; đó
     chính là lý do script này đếm."
    fi

    tong_ca=$((tong_ca + chay))
done

ok "$tong_ca ca xanh trên $ANH_CHAY  ($IT)."
