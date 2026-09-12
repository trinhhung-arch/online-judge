/**
 * Kịch bản ỔN ĐỊNH — nfrplan 2.4 (b): "tải ổn định 2 bài/s trong 30 phút". Chạy qua `chay.sh on-dinh`.
 *
 * ★ ĐÂY LÀ KỊCH BẢN DUY NHẤT MÀ P3 VÀ P6 CÓ NGHĨA. P3 là "hàng đợi rỗng", P6 là "tải bình thường";
 *   2 bài/s dưới năng lực chấm ~5.8 bài/s nên hàng đợi gần như luôn rỗng. Ở kịch bản quét, cả
 *   hai con số chỉ là thời gian xếp hàng. P3/P6 lấy từ DB (bao-cao-db.py), không lấy ở đây.
 *
 * ★ MÔ HÌNH MỞ (constant-arrival-rate). Bài đến đúng TOC_DO mỗi giây bất kể máy chủ nhanh hay
 *   chậm — người thật không chờ bài người khác chấm xong mới nộp. Mô hình đóng tự giảm tải khi
 *   máy chủ chậm và làm đẹp p95 (coordinated omission). `dropped_iterations` khác 0 = k6 không
 *   phát nổi TOC_DO, và tải thật thấp hơn tải khai báo.
 *
 * ★ BỂ TÀI KHOẢN. Người ảo id v sở hữu KHOI tài khoản riêng và xoay vòng (luật 2 trong chung.js).
 *   KHOI = TOC_DO × 10s × 1.25, nên kể cả khi k6 dồn MỌI vòng lặp vào một người ảo, một tài khoản
 *   vẫn nộp cách nhau ≥ 12.5s — rate limit 10s không bao giờ chạm. `ti_le_429` khác 0 là bể sai.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { NGUONG, DE_MA, diaChiDich, dangNhapTheoLo, layToken, timDe, nopBai, giamSatHangDoi }
    from './chung.js';
import { nguongK6, tomTat } from './ket-luan.js';

const BASE = diaChiDich();
const TOC_DO = Number(__ENV.TOC_DO || 2);
const TOC_DO_DOC = Number(__ENV.TOC_DO_DOC || 5);
const THOI_LUONG = __ENV.THOI_LUONG || '30m';
const VU_NOP = Number(__ENV.VU_NOP || 10);
const VU_DOC = Number(__ENV.VU_DOC || 10);
const KHOI = Math.ceil(TOC_DO * 10 * 1.25);
// id người ảo là toàn cục qua mọi scenario, nên bể phải phủ cả người ảo đọc và người giám sát.
const BE = (VU_NOP + VU_DOC + 1) * KHOI;
const BO_QUA_DAU_MS = 60000;   // JIT và cache Postgres của phút đầu không thuộc "trạng thái ổn định"

const docMs = new Trend('doc_ms', true);
const dangDo = () => exec.instance.currentTestRunDuration >= BO_QUA_DAU_MS;
const HONG_TOI_DA = NGUONG.cung.find((d) => d.metric.startsWith('http_req_failed')).toi_da;

export const options = {
    setupTimeout: `${Math.ceil(BE / 3 * 0.5) + 60}s`,
    scenarios: {
        tai: {
            executor: 'constant-arrival-rate', exec: 'nop', rate: TOC_DO, timeUnit: '1s',
            duration: THOI_LUONG, preAllocatedVUs: Math.ceil(VU_NOP / 2), maxVUs: VU_NOP,
        },
        doc: {
            executor: 'constant-arrival-rate', exec: 'doc', rate: TOC_DO_DOC, timeUnit: '1s',
            duration: THOI_LUONG, preAllocatedVUs: Math.ceil(VU_DOC / 2), maxVUs: VU_DOC,
        },
        giam_sat: { executor: 'constant-vus', exec: 'giamSat', vus: 1, duration: THOI_LUONG },
    },
    thresholds: {
        ...nguongK6(NGUONG, 'on-dinh'),
        'http_req_failed{scenario:doc}': [`rate<${HONG_TOI_DA}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
    if (BE > Number(__ENV.SO_TAI_KHOAN || 1000)) {
        throw new Error(`Cần ${BE} tài khoản ảo, mới seed ${__ENV.SO_TAI_KHOAN || 1000}. `
            + 'Seed thêm, hoặc giảm VU_NOP/VU_DOC.');
    }
    const handles = Array.from({ length: BE }, (_, i) => `tai-${i + 1}`);
    return { problemId: timDe(BASE), phien: dangNhapTheoLo(BASE, handles) };
}

let luotCuaVu = 0;

export function nop(du) {
    const handle = `tai-${(__VU - 1) * KHOI + (luotCuaVu++ % KHOI) + 1}`;
    const r = nopBai(BASE, layToken(BASE, handle, du.phien), du.problemId, dangDo());
    check(r, { 'nộp bài 202': (x) => x.status === 202 });
}

/** Ẩn danh: hai endpoint này công khai, và đọc không cần gắn với bể tài khoản của người nộp. */
export function doc() {
    const url = Math.random() < 0.5
        ? `${BASE}/api/v1/problems?size=20`
        : `${BASE}/api/v1/problems/${encodeURIComponent(DE_MA)}`;
    const res = http.get(url, { tags: { viec: 'doc' } });
    if (dangDo()) docMs.add(res.timings.duration);
    check(res, { 'đọc đề 200': (x) => x.status === 200 });
}

export function giamSat() {
    giamSatHangDoi(BASE);
}

export function handleSummary(data) {
    return tomTat(data, NGUONG, {
        kichBan: 'on-dinh', base: BASE, raJson: __ENV.RA_JSON,
        moTa: `${TOC_DO} bài/s + ${TOC_DO_DOC} lượt đọc/s · ${THOI_LUONG} · mô hình mở · bể ${BE} tài khoản`,
    });
}
