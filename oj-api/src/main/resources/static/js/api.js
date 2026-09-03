/**
 * Lớp gọi API — Bước 4.12.
 *
 * ★ BA VIỆC, và mỗi việc tồn tại vì một quyết định ở phía server:
 *
 *  1. Tự làm mới access token khi nhận `auth.token_het_han`.
 *     Server cố ý tách mã đó khỏi `auth.token_khong_hop_le` (xem AuthorizationException)
 *     để đây phân biệt được "cần refresh" với "phải đăng nhập lại". Gộp hai mã làm một
 *     thì người dùng bị đá ra mỗi 15 phút.
 *
 *  2. Đưa `message` của server ra thẳng cho người dùng.
 *     GlobalExceptionHandler đã bảo đảm publicMessage là câu duy nhất được phép ra ngoài
 *     và nó đã bằng tiếng Việt. Frontend tự dịch mã lỗi thành câu chữ là tạo ra hai bản
 *     dịch cho một lỗi, và bản ở đây sẽ lạc hậu trước.
 *
 *  3. Không bao giờ ghi token vào URL.
 *     URL đi vào log của proxy, vào lịch sử trình duyệt, vào ảnh chụp màn hình khi báo lỗi.
 */

import { DUONG } from './duong-dan.js';

const KHOA_PHIEN = 'oj.phien';

export function phien() {
    try {
        return JSON.parse(localStorage.getItem(KHOA_PHIEN) || 'null');
    } catch {
        return null;
    }
}

export function luuPhien(p) {
    localStorage.setItem(KHOA_PHIEN, JSON.stringify(p));
}

export function xoaPhien() {
    localStorage.removeItem(KHOA_PHIEN);
}

/** Lỗi mang theo mã ổn định của server, để nơi gọi xử lý theo chương trình nếu cần. */
export class LoiApi extends Error {
    constructor(status, code, message, retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }
}

async function doc(res) {
    if (res.status === 204) return null;
    const kieu = res.headers.get('content-type') || '';
    if (!kieu.includes('json')) return null;
    return res.json().catch(() => null);
}

async function lamMoi() {
    const p = phien();
    if (!p?.refreshToken) return false;
    const res = await fetch(DUONG.auth.lamMoi, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: p.refreshToken }),
    });
    if (!res.ok) {
        // Refresh token cũng chết: hoặc hết 7 ngày, hoặc server phát hiện nó bị dùng lại
        // và đã thu hồi TOÀN BỘ phiên (xem RefreshSessionUseCase). Cả hai đều là đăng nhập lại.
        xoaPhien();
        return false;
    }
    luuPhien(await res.json());
    return true;
}

/**
 * @param {string} duongDan  ví dụ '/api/v1/problems'
 * @param {object} tuyChon   { method, body, khongLamMoi }
 */
export async function goi(duongDan, tuyChon = {}) {
    const p = phien();
    const headers = { ...(tuyChon.headers || {}) };
    if (p?.accessToken) headers.Authorization = `Bearer ${p.accessToken}`;
    if (tuyChon.body !== undefined && !(tuyChon.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }

    const res = await fetch(duongDan, {
        method: tuyChon.method || 'GET',
        headers,
        body: tuyChon.body instanceof FormData
            ? tuyChon.body
            : (tuyChon.body !== undefined ? JSON.stringify(tuyChon.body) : undefined),
    });

    if (res.ok) return doc(res);

    const than = await doc(res);
    const code = than?.code;

    // ★ Đúng một lần thử lại. Vòng lặp vô hạn ở đây là một cách tự tấn công server.
    if (code === 'auth.token_het_han' && !tuyChon.khongLamMoi) {
        if (await lamMoi()) {
            return goi(duongDan, { ...tuyChon, khongLamMoi: true });
        }
    }

    throw new LoiApi(
        res.status,
        code,
        than?.message || 'Có lỗi xảy ra. Thử lại sau.',
        res.headers.get('Retry-After'));
}

/** Token hiện tại, cho luồng SSE — xem js/sse.js. */
export function accessToken() {
    return phien()?.accessToken || null;
}
