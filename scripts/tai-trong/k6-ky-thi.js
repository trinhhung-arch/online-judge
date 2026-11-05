import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { NGUONG, DE_MA, diaChiDich, timDe, nopBai, giamSatHangDoi } from './chung.js';
import { nguongK6, tomTat } from './ket-luan.js';

const BASE = diaChiDich();
if (!__ENV.PHIEN) throw new Error('Thiếu -e PHIEN=<file phiên do sse-tai.py ghi>. Chạy qua chay.sh ky-thi.');
const PHIEN = JSON.parse(open(__ENV.PHIEN));
const SO_PHIEN = Object.keys(PHIEN).length;
const TOC_DO = Number(__ENV.TOC_DO || 3);
const TOC_DO_DOC = Number(__ENV.TOC_DO_DOC || 5);
const THOI_LUONG = __ENV.THOI_LUONG || '3m';
const BO_QUA_DAU_MS = 15000;

const docMs = new Trend('doc_ms', true);
const dangDo = () => exec.instance.currentTestRunDuration >= BO_QUA_DAU_MS;
const HONG_TOI_DA = NGUONG.cung.find((d) => d.metric.startsWith('http_req_failed')).toi_da;

export const options = {
    scenarios: {
        tai: {
            executor: 'constant-arrival-rate', exec: 'nop', rate: TOC_DO, timeUnit: '1s',
            duration: THOI_LUONG, preAllocatedVUs: Math.max(5, TOC_DO * 3), maxVUs: Math.max(20, TOC_DO * 10),
        },
        doc: {
            executor: 'constant-arrival-rate', exec: 'doc', rate: TOC_DO_DOC, timeUnit: '1s',
            duration: THOI_LUONG, preAllocatedVUs: 5, maxVUs: 20,
        },
        giam_sat: { executor: 'constant-vus', exec: 'giamSat', vus: 1, duration: THOI_LUONG },
    },
    thresholds: {
        ...nguongK6(NGUONG, 'ky-thi'),
        'http_req_failed{scenario:doc}': [`rate<${HONG_TOI_DA}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
    if (SO_PHIEN === 0 || SO_PHIEN / TOC_DO < 12.5) {
        throw new Error(`${SO_PHIEN} tài khoản cho ${TOC_DO} bài/s: một tài khoản nộp lại sau `
            + `${(SO_PHIEN / TOC_DO).toFixed(1)}s < 12.5s và chạm rate limit 10s. Tăng SO_NGUOI hoặc giảm TOC_DO.`);
    }
    return { problemId: timDe(BASE) };
}

export function nop(du) {
    const handle = `tai-${(exec.scenario.iterationInTest % SO_PHIEN) + 1}`;
    const p = PHIEN[handle];
    if (!p || Date.now() > p.het - 30000) {
        throw new Error(`Phiên của ${handle} thiếu hoặc sắp hết hạn — lượt đo dài hơn access-ttl?`);
    }
    const r = nopBai(BASE, p.access, du.problemId, dangDo());
    check(r, { 'nộp bài 202': (x) => x.status === 202 });
}

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
        kichBan: 'ky-thi', base: BASE, raJson: __ENV.RA_JSON,
        moTa: `${TOC_DO} bài/s + ${TOC_DO_DOC} lượt đọc/s · ${THOI_LUONG} · ${SO_PHIEN} người đang xem`,
    });
}
