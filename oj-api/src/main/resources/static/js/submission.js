/**
 * Chi tiết bài nộp, cập nhật realtime — FR-SUB-05, 06. Bước 4.12.
 *
 * ★ SSE LÀ ĐƯỜNG CHÍNH, POLLING LÀ ĐƯỜNG BẮT BUỘC PHẢI CÓ
 *
 * `oj-api/CLAUDE.md` mục 4: <i>"Fallback REST bắt buộc. Kết nối SẼ đứt — Cloudflare Tunnel
 * giới hạn thời gian sống. Không có fallback = tính năng chưa xong."</i>
 *
 * Nên trang này có hai nguồn sự thật và một quy tắc rõ ràng giữa chúng: sự kiện SSE cập nhật
 * phần trạng thái ngay lập tức, còn REST là thứ dựng lại toàn bộ chi tiết. Khi luồng đứt,
 * polling 3 giây tiếp quản cho tới khi bài chấm xong.
 */

import { goi, accessToken, LoiApi } from './api.js';
import { veThanh, bao, chu, thamSo, canDangNhap } from './khung.js';
import { moLuong } from './sse.js';
import { DUONG } from './duong-dan.js';

veThanh();

const id = thamSo('id');
const o = document.getElementById('thong-bao');
const oTrangThai = document.getElementById('trang-thai');
const oVerdict = document.getElementById('verdict');
const oTienDo = document.getElementById('tien-do');
const oGiaiThich = document.getElementById('giai-thich');
const oChiTiet = document.getElementById('chi-tiet');

let dongLuong = null;
let henPoll = null;

const NHAN_TRANG_THAI = {
    QUEUED: 'Đang chờ chấm',
    JUDGING: 'Đang chấm',
    DONE: 'Đã chấm xong',
};

function xong(trangThai) {
    return trangThai === 'DONE';
}

function veTrangThai(e) {
    oTrangThai.textContent = NHAN_TRANG_THAI[e.status] || e.status;
    oVerdict.textContent = e.verdict || '';
    oVerdict.className = `verdict ${e.verdict || 'cho'}`;

    if (e.status === 'JUDGING' && e.totalTests) {
        oTienDo.textContent = `Đã chạy ${e.testsDone}/${e.totalTests} test.`;
    } else if (!xong(e.status)) {
        oTienDo.textContent = 'Chưa có tiến độ — bài đang chờ một máy chấm rảnh.';
    } else {
        oTienDo.textContent = '';
    }
}

function hang(nhan, giaTri) {
    const tr = chu('tr');
    const th = chu('th', nhan);
    th.scope = 'row';
    tr.append(th, chu('td', giaTri));
    return tr;
}

function veChiTiet(ct) {
    oGiaiThich.textContent = ct.explanation || '';

    const hangs = [];
    hangs.push(hang('Trạng thái', NHAN_TRANG_THAI[ct.status] || ct.status));
    if (ct.verdict) hangs.push(hang('Kết quả', ct.verdict));
    if (ct.score !== null && ct.maxScore !== null) {
        hangs.push(hang('Điểm', `${ct.score} / ${ct.maxScore}`));
    }
    if (ct.timeMs !== null) hangs.push(hang('Thời gian', `${ct.timeMs} ms`));
    if (ct.memoryKb !== null) {
        hangs.push(hang('Bộ nhớ', `${Math.round(ct.memoryKb / 1024)} MB`));
    }
    if (ct.measurementNote) hangs.push(hang('Ghi chú đo', ct.measurementNote));
    // Server đã lọc theo feedback_level (FR-PROB-07): trường này là null khi đề đặt NONE.
    // Frontend KHÔNG tự quyết định hiện gì — nó chỉ hiện thứ được gửi tới.
    if (ct.failedTestOrdinal !== null && ct.failedTestOrdinal !== undefined) {
        hangs.push(hang('Sai từ test', String(ct.failedTestOrdinal)));
    }
    hangs.push(hang('Lần chấm', String(ct.attempt)));
    oChiTiet.replaceChildren(...hangs);

    const khuCompile = document.getElementById('khu-compile');
    if (ct.compileLog) {
        document.getElementById('compile-log').textContent = ct.compileLog;
        khuCompile.hidden = false;
    } else {
        khuCompile.hidden = true;
    }

    document.getElementById('tieu-de').textContent = `Bài nộp #${ct.submissionId}`;
    document.title = `Bài nộp #${ct.submissionId} · Online Judge`;
}

async function taiChiTiet() {
    const ct = await goi(DUONG.baiNop.theoId(id));
    veChiTiet(ct);
    veTrangThai(ct);
    return ct;
}

function ngungTheoDoi() {
    dongLuong?.();
    dongLuong = null;
    clearTimeout(henPoll);
}

/** Đường dự phòng: 3 giây một lần, và dừng ngay khi bài chấm xong. */
function poll() {
    clearTimeout(henPoll);
    henPoll = setTimeout(async () => {
        try {
            const ct = await taiChiTiet();
            if (!xong(ct.status)) poll();
        } catch {
            poll();   // sự cố tạm thời không được làm trang đứng im mãi mãi
        }
    }, 3000);
}

function theoDoi() {
    dongLuong = moLuong(DUONG.baiNop.luong(id), accessToken(), {
        onSuKien: (ten, e) => {
            if (!e) return;
            veTrangThai(e);
            if (xong(e.status)) {
                ngungTheoDoi();
                taiChiTiet().catch(() => {});   // lấy nốt phần chi tiết SSE cố ý không mang
            }
        },
        onDut: () => {
            // Không báo lỗi cho người dùng: đứt kết nối là chuyện BÌNH THƯỜNG với tunnel,
            // và một dòng cảnh báo đỏ mỗi năm phút chỉ dạy người ta bỏ qua cảnh báo.
            dongLuong = null;
            poll();
        },
    });
}

(async () => {
    if (!id) {
        bao(o, 'Thiếu mã bài nộp trên đường dẫn.', 'loi');
        return;
    }
    if (!canDangNhap()) return;
    try {
        const ct = await taiChiTiet();
        if (!xong(ct.status)) theoDoi();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được bài nộp.', 'loi');
    }
})();

// Rời trang thì đóng luồng. Thiếu dòng này là để lại một listener Redis và một tác vụ nhịp
// tim ở server cho mỗi lần người dùng bấm F5 — và đây là trang người ta bấm F5 nhiều nhất.
window.addEventListener('pagehide', ngungTheoDoi);
