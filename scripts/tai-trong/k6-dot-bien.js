/**
 * Kịch bản ĐỘT BIẾN — nfrplan 2.4 (a) và P5: "500 bài nộp đồng thời, rút cạn < 3 phút, 0 bài mất".
 * Chạy qua `chay.sh dot-bien`.
 *
 * ★ per-vu-iterations: SO_BAI người ảo, mỗi người đúng MỘT bài, cùng xuất phát. Người ảo id v sở
 *   hữu tai-v, nên không ai chạm rate limit — 500 bài là 500 bài, không phải 50 bài và 450 lần 429.
 *   Bản cũ không có đợt này: ô "rút cạn" đo phần tồn BẤT KỲ của kịch bản quét (hàng nghìn bài),
 *   nên không so được với P5.
 *
 * ★ NGHIỆM THU ĐỢT NÀY LÀ P5 + R1 (nfrplan Phần 14: "rút cạn < 3 phút, 0 bài mất"), cùng R2, R3.
 *   bao-cao-db.py đếm trong DB sau khi chay.sh chờ rút cạn. k6 chỉ chấm tỉ lệ request hỏng.
 *
 * ★ P2 Ở ĐÂY LÀ QUAN SÁT, KHÔNG CHẤM. P2 là p95 của tải thật (Micrometer, alert > 800ms/5 phút).
 *   500 request đến cùng một mili-giây thì p95 là thời gian XẾP HÀNG ≈ 0,95 × 500 ÷ tốc độ API ghi
 *   nhận bài — con số năng lực, không phải độ trễ người dùng gặp. Đo 2026-09-11: p95 577ms, connect
 *   p95 12ms, DB ghi nhận 500 bài trong 600ms. Bản đầu chấm P2 ở đây và in ❌ cho một lượt đạt.
 */
import { check } from 'k6';
import { NGUONG, diaChiDich, dangNhapTheoLo, layToken, timDe, nopBai, giamSatHangDoi } from './chung.js';
import { nguongK6, tomTat } from './ket-luan.js';

const BASE = diaChiDich();
const SO_BAI = Number(__ENV.SO_BAI || 500);

export const options = {
    setupTimeout: `${Math.ceil((SO_BAI + 1) / 3 * 0.5) + 60}s`,
    scenarios: {
        tai: { executor: 'per-vu-iterations', exec: 'nop', vus: SO_BAI, iterations: 1, maxDuration: '2m' },
        giam_sat: { executor: 'constant-vus', exec: 'giamSat', vus: 1, duration: '30s' },
    },
    thresholds: nguongK6(NGUONG, 'dot-bien'),
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

/** SO_BAI + 1 tài khoản: id người ảo toàn cục qua hai scenario, người giám sát chiếm một id. */
export function setup() {
    const handles = Array.from({ length: SO_BAI + 1 }, (_, i) => `tai-${i + 1}`);
    return { problemId: timDe(BASE), phien: dangNhapTheoLo(BASE, handles) };
}

export function nop(du) {
    const r = nopBai(BASE, layToken(BASE, `tai-${__VU}`, du.phien), du.problemId, true);
    check(r, { 'nộp bài 202': (x) => x.status === 202 });
}

export function giamSat() {
    giamSatHangDoi(BASE);
}

export function handleSummary(data) {
    return tomTat(data, NGUONG, {
        kichBan: 'dot-bien', base: BASE, raJson: __ENV.RA_JSON, nguoi: SO_BAI,
        moTa: `${SO_BAI} bài nộp cùng lúc · mỗi tài khoản đúng 1 bài`,
    });
}
