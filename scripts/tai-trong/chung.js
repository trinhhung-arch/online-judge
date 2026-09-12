/**
 * Phần dùng chung của ba kịch bản k6 (`k6-tai.js` · `k6-on-dinh.js` · `k6-dot-bien.js`).
 *
 * ★ BA LUẬT MÀ MỌI KỊCH BẢN PHẢI THEO — cả ba đều từng sai và cho ra số giả
 *
 * 1. KHÔNG CÓ BASE MẶC ĐỊNH, KHÔNG ĐO QUA TÊN MIỀN CÔNG KHAI. Ngày 2026-09-11 cổng 8080 của máy
 *    này trở thành API prod (`ojdb_prod`, công khai qua tunnel) — đúng cái địa chỉ từng là mặc
 *    định ở đây. Đo qua tunnel còn tệ hơn: số đo gồm cả Cloudflare, và Cloudflare có thể chặn.
 *
 * 2. MỖI TÀI KHOẢN ẢO THUỘC ĐÚNG MỘT NGƯỜI ẢO. Refresh token XOAY VÒNG, và trình lại một token
 *    đã thu hồi là thu hồi TOÀN BỘ phiên của tài khoản đó (RefreshSessionUseCase). Hai người ảo
 *    dùng chung một tài khoản thì lần làm mới thứ hai đăng xuất cả hai — ở phút 15 của kịch bản
 *    30 phút (access-ttl 15m), và mọi request sau đó 401.
 *
 * 3. ĐĂNG NHẬP XONG TRƯỚC KHI ĐO. bcrypt cost 12 ≈ 250ms CPU mỗi lần. Đăng nhập trong lúc đo là
 *    đo một máy chủ đang bận băm mật khẩu. `setup()` đăng nhập hết, theo lô nhỏ hơn trần
 *    `bcrypt-concurrency` (4), rồi mới tới giai đoạn đo.
 */
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

export const NGUONG = JSON.parse(open('./nguong.json'));

// 429 là rate limit làm đúng việc (FR-SUB-08), không phải request hỏng.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

export const MAT_KHAU = __ENV.MAT_KHAU || 'matkhau-dev-123';
export const NGON_NGU = __ENV.NGON_NGU || 'cpp20';
export const DE_MA = __ENV.DE_MA || 'A-PLUS-B';

/** Dưới `oj.auth.bcrypt-concurrency` (4): chạm trần là 429 ngay, không xếp hàng. */
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

/** Luật 1. Chạy ở init context, nên k6 dừng trước khi gửi request đầu tiên. */
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

/** Luật 3. Thử lại có giới hạn khi chạm trần bcrypt; mọi lỗi khác là dừng cả lượt. */
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

/** Luật 2 — chỉ gọi với tài khoản mà người ảo này sở hữu. */
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

/** Hỏi đề trong setup: sai mã đề thì dừng ngay, đừng để cả lượt nộp vào một đề 404. */
export function timDe(base) {
    const r = http.get(`${base}/api/v1/problems/${encodeURIComponent(DE_MA)}`);
    if (r.status !== 200) throw new Error(`Đề ${DE_MA} → ${r.status} trên ${base}.`);
    if (!r.json('acceptsSubmissions')) throw new Error(`Đề ${DE_MA} không nhận bài nộp.`);
    return r.json('problemId');
}

/**
 * Bài A+B. `// EXPECT: AC` là dòng đầu cho ScriptedJudgeRunner và chỉ là comment với isolate.
 * Token duy nhất ở CUỐI để CompileCache (khoá sha256 source) không trả lời thay máy chấm —
 * đo 2026-09-05: source trùng nhau cho verdict p95 350ms, tức là đo tốc độ của cache.
 */
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
        // Đếm MỌI lần 202, kể cả lúc dốc lên: đây là vế client của phép đối soát R1 với DB.
        nop202.add(1);
        if (dangDo) nopMs.add(res.timings.duration);
    }
    return res;
}

/** Dụng cụ đo: một người ảo, mỗi giây một lần, endpoint công khai và rẻ. */
export function giamSatHangDoi(base) {
    const res = http.get(`${base}/api/v1/status`, { tags: { viec: 'giam-sat' } });
    if (res.status === 200) {
        hangDoi.add(res.json('dangCho'));
        // KHÔNG PHẢI P6: đây là tuổi của bài đang chờ lâu nhất ở thời điểm hỏi. P6 là p95 thời
        // gian chờ của TỪNG bài — bao-cao-db.py tính nó từ judge_runs.started_at.
        tuoiBaiChoLauNhat.add(res.json('choLauNhatMs'));
        mayChamSong.add(res.json('mayChamSong'));
    }
    sleep(1);
}
