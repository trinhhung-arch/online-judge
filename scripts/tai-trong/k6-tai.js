/**
 * Kịch bản QUÉT — "đường API chịu được bao nhiêu người đồng thời".
 *
 * Chạy qua `chay.sh quet`, đừng chạy lẻ: chay.sh mới là nơi chờ rút cạn, chụp mốc id cho DB và
 * gọi bao-cao-db.py. Chạy lẻ thì chỉ có nửa k6 của kết quả.
 *
 * ★ KỊCH BẢN NÀY TRẢ LỜI GÌ, VÀ KHÔNG TRẢ LỜI GÌ
 *   Trả lời: P1, P2 theo số người ảo; và R1/R2/R3 (qua DB) dưới quá tải.
 *   KHÔNG trả lời P3, P6 — nó cố ý quá tải: rate limit cho N người chào N/10 bài/s, năng lực
 *   chấm ~5.8 bài/s, nên từ 100 người hàng đợi đã dài. P3/P6 ở đây là thời gian xếp hàng.
 *   Muốn P3/P6 thật: `chay.sh on-dinh`. P5: `chay.sh dot-bien`. SSE: `chay.sh sse`.
 *
 * ★ MÔ HÌNH ĐÓNG (ramping-vus + thời gian nghỉ) LÀ CỐ Ý Ở ĐÂY. Câu hỏi là "bao nhiêu NGƯỜI", và
 *   người thật thì chờ trang trả về rồi mới bấm tiếp. Cái giá phải biết: máy chủ chậm thì người
 *   ảo gửi ít đi, tải tự giảm. Câu hỏi theo TỐC ĐỘ ĐẾN là việc của k6-on-dinh.js.
 *
 * ★ KHÔNG HỎI VERDICT Ở CLIENT NỮA. Bản cũ hỏi theo nhịp 0.25→0.65→1.29→2.31s, nên verdict thật
 *   1.3s bị ghi 2.31s; và nó chỉ theo 10% số bài. bao-cao-db.py đọc judged_at − created_at của
 *   MỌI bài từ Postgres — đúng tới mili giây, và không tốn thêm request nào trong lúc đo.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { NGUONG, DE_MA, diaChiDich, dangNhapTheoLo, layToken, timDe, nopBai, giamSatHangDoi, tieuDe }
    from './chung.js';
import { nguongK6, tomTat } from './ket-luan.js';

const BASE = diaChiDich();
const NGUOI = Number(__ENV.NGUOI || 100);
const TI_LE_NOP = Number(__ENV.TI_LE_NOP || 0.3);
const THOI_LUONG = __ENV.THOI_LUONG || '3m';
const DOC_LEN = __ENV.DOC_LEN || '30s';

function giay(d) {
    const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(d).trim());
    if (!m) throw new Error(`Không đọc được thời lượng: ${d} (ví dụ: 3m, 180s)`);
    return Number(m[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2]];
}
const DOC_LEN_MS = giay(DOC_LEN) * 1000;

/**
 * p95 chỉ tính từ lúc đã đủ người. Request ở giây thứ 5 gặp một máy chủ gần như rỗng; gộp vào là
 * pha loãng theo hướng LÀM ĐẸP, và mức càng đông thì càng được làm đẹp nhiều.
 */
function dangDo() {
    return exec.instance.currentTestRunDuration >= DOC_LEN_MS;
}

const docMs = new Trend('doc_ms', true);

export const options = {
    // Đăng nhập theo lô 3 · ~0.3s mỗi lô. Thiếu dòng này thì k6 cắt setup ở 60s mặc định.
    setupTimeout: `${Math.ceil((NGUOI + 1) / 3 * 0.5) + 60}s`,
    scenarios: {
        tai: {
            executor: 'ramping-vus',
            exec: 'nguoiDung',
            startVUs: 0,
            stages: [
                { duration: DOC_LEN, target: NGUOI },
                { duration: THOI_LUONG, target: NGUOI },
                { duration: '15s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },
        giam_sat: {
            executor: 'constant-vus',
            exec: 'giamSat',
            vus: 1,
            duration: `${giay(DOC_LEN) + giay(THOI_LUONG) + 15}s`,
        },
    },
    thresholds: nguongK6(NGUONG, 'quet'),
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

/**
 * Đăng nhập NGUOI + 1 tài khoản: id của người ảo là toàn cục qua CẢ HAI scenario (1..NGUOI+1), và
 * người ảo id v sở hữu tai-v. Bản cũ dùng `% NGUOI` nên hai người ảo có thể trùng một tài khoản.
 */
export function setup() {
    const problemId = timDe(BASE);
    const handles = Array.from({ length: NGUOI + 1 }, (_, i) => `tai-${i + 1}`);
    return { problemId, phien: dangNhapTheoLo(BASE, handles) };
}

/** P1 là "đọc đề, danh sách" — cả hai, không chỉ danh sách. */
function doc(token) {
    const url = Math.random() < 0.5
        ? `${BASE}/api/v1/problems?size=20`
        : `${BASE}/api/v1/problems/${encodeURIComponent(DE_MA)}`;
    const res = http.get(url, { headers: tieuDe(token), tags: { viec: 'doc' } });
    if (dangDo()) docMs.add(res.timings.duration);
    check(res, { 'đọc đề 200': (r) => r.status === 200 });
}

export function nguoiDung(du) {
    const token = layToken(BASE, `tai-${__VU}`, du.phien);
    if (Math.random() < TI_LE_NOP) {
        const r = nopBai(BASE, token, du.problemId, dangDo());
        check(r, { 'nộp bài 202 hoặc 429': (x) => x.status === 202 || x.status === 429 });
    } else {
        doc(token);
    }
    sleep(1 + Math.random() * 2);
}

export function giamSat() {
    giamSatHangDoi(BASE);
}

export function handleSummary(data) {
    return tomTat(data, NGUONG, {
        kichBan: 'quet', nguoi: NGUOI, thoiLuong: THOI_LUONG, docLen: DOC_LEN,
        base: BASE, tiLeNop: TI_LE_NOP, raJson: __ENV.RA_JSON,
    });
}
