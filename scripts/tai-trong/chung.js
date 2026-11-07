import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

export const NGUONG = JSON.parse(open('./nguong.json'));

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

export const MAT_KHAU = __ENV.MAT_KHAU || 'matkhau-dev-123';
export const NGON_NGU = __ENV.NGON_NGU || 'cpp20';
export const DE_MA = __ENV.DE_MA || 'A-PLUS-B';

const LO_BCRYPT = 3;
const JSON_HDR = { 'Content-Type': 'application/json' };

export const nopMs = new Trend('nop_ms', true);
export const nop202 = new Counter('nop_202');
export const ti429 = new Rate('ti_le_429');
export const hangDoi = new Trend('hang_doi');
export const tuoiBaiChoLauNhat = new Trend('tuoi_bai_cho_lau_nhat_ms', true);
export const mayChamSong = new Trend('may_cham_song');

function laMayNoiBo(host) {
    if (host === 'localhost' || host === '::1' || host.endsWith('.local')) return true;
    const p = host.split('.').map(Number);
    if (p.length !== 4 || p.some((x) => !Number.isInteger(x) || x < 0 || x > 255)) return false;
    return p[0] === 127 || p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31)
        || (p[0] === 192 && p[1] === 168);
}

export function diaChiDich() {
    const base = __ENV.BASE;
    if (!base) {
        throw new Error('Thiếu -e BASE=http://<máy-đo-riêng>:<cổng>. Không có mặc định, cố ý: '
            + 'localhost:8080 của máy chủ là API prod.');
    }
    const m = /^https?:\/\/(\[[^\]]+\]|[^/:]+)(:\d+)?\/?$/.exec(base);
    if (!m) throw new Error(`BASE không hợp lệ: ${base}`);
    const host = m[1].replace(/^\[|\]$/g, '');
    if (!laMayNoiBo(host) && __ENV.CHO_PHEP_DICH_CONG_KHAI !== base) {
        throw new Error(`BASE ${base} không phải máy nội bộ. Load test qua tên miền công khai đo `
            + 'cả Cloudflare và bắn vào người dùng thật. Cố ý làm thì đặt '
            + `CHO_PHEP_DICH_CONG_KHAI=${base}`);
    }
    return base.replace(/\/$/, '');
}

export function tieuDe(token) {
    return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

function phienTu(res) {
    return {
        access: res.json('accessToken'),
        refresh: res.json('refreshToken'),
        het: Date.now() + Number(res.json('expiresIn')) * 1000,
    };
}

export function dangNhapTheoLo(base, handles) {
    const phien = {};
    for (let i = 0; i < handles.length; i += LO_BCRYPT) {
        let lo = handles.slice(i, i + LO_BCRYPT);
        for (let lan = 0; lo.length > 0; lan++) {
            if (lan >= 5) throw new Error(`Đăng nhập ${lo.join(', ')} vẫn 429 sau 5 lần thử.`);
            const res = http.batch(lo.map((h) => ['POST', `${base}/api/v1/auth/login`,
                JSON.stringify({ dinhDanh: h, password: MAT_KHAU }),
                { headers: JSON_HDR, tags: { viec: 'dang-nhap-setup' } }]));
            const conLai = [];
            res.forEach((r, k) => {
                if (r.status === 200) phien[lo[k]] = phienTu(r);
                else if (r.status === 429) conLai.push(lo[k]);
                else {
                    throw new Error(`Đăng nhập ${lo[k]} → ${r.status}. Chưa chạy `
                        + 'seed-nguoi-dung.sql với đủ so_nguoi, hoặc BASE trỏ nhầm máy chủ.');
                }
            });
            lo = conLai;
            if (lo.length) sleep(1);
        }
    }
    return phien;
}

const phienCuaVu = {};

export function layToken(base, handle, phienBanDau) {
    let p = phienCuaVu[handle] || phienBanDau[handle];
    if (!p) throw new Error(`Không có phiên cho ${handle} — setup() chưa đăng nhập tài khoản này.`);
    if (Date.now() > p.het - 60000) {
        const r = http.post(`${base}/api/v1/auth/refresh`, JSON.stringify({ refreshToken: p.refresh }),
            { headers: JSON_HDR, tags: { viec: 'lam-moi' } });
        if (r.status !== 200) {
            throw new Error(`Làm mới phiên ${handle} → ${r.status}. Có hai người ảo dùng chung `
                + 'tài khoản này không? (luật 2 ở đầu chung.js)');
        }
        p = phienTu(r);
    }
    phienCuaVu[handle] = p;
    return p.access;
}

export function timDe(base) {
    const r = http.get(`${base}/api/v1/problems/${encodeURIComponent(DE_MA)}`);
    if (r.status !== 200) throw new Error(`Đề ${DE_MA} → ${r.status} trên ${base}.`);
    if (!r.json('acceptsSubmissions')) throw new Error(`Đề ${DE_MA} không nhận bài nộp.`);
    return r.json('problemId');
}

const NGUON_GOC = `// EXPECT: AC
#include <bits/stdc++.h>
int main(){long long a,b;if(!(std::cin>>a>>b))return 0;std::cout<<a+b<<"\\n";}
`;

export function nopBai(base, token, problemId, dangDo) {
    const nguon = __ENV.NGUON_DUY_NHAT === '0' ? NGUON_GOC
        : `${NGUON_GOC}// duy-nhat ${__VU}-${__ITER}-${Date.now()}-${Math.random()}\n`;
    const res = http.post(`${base}/api/v1/submissions`,
        JSON.stringify({ problemId, languageCode: NGON_NGU, source: nguon }),
        { headers: tieuDe(token), tags: { viec: 'nop' } });
    ti429.add(res.status === 429);
    if (res.status === 202) {
        nop202.add(1);
        if (dangDo) nopMs.add(res.timings.duration);
    }
    return res;
}

export function giamSatHangDoi(base) {
    const res = http.get(`${base}/api/v1/status`, { tags: { viec: 'giam-sat' } });
    if (res.status === 200) {
        hangDoi.add(res.json('dangCho'));
        tuoiBaiChoLauNhat.add(res.json('choLauNhatMs'));
        mayChamSong.add(res.json('mayChamSong'));
    }
    sleep(1);
}
