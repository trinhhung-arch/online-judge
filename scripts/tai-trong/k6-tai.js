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

function dangDo() {
    return exec.instance.currentTestRunDuration >= DOC_LEN_MS;
}

const docMs = new Trend('doc_ms', true);

export const options = {
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

export function setup() {
    const problemId = timDe(BASE);
    const handles = Array.from({ length: NGUOI + 1 }, (_, i) => `tai-${i + 1}`);
    return { problemId, phien: dangNhapTheoLo(BASE, handles) };
}

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
