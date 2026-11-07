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
