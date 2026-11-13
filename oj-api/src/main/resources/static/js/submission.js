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
    } else if (e.status === 'JUDGING') {
        oTienDo.textContent = 'Đang chấm.';
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

function poll() {
    clearTimeout(henPoll);
    henPoll = setTimeout(async () => {
        try {
            const ct = await taiChiTiet();
            if (!xong(ct.status)) poll();
        } catch {
            poll();
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
                taiChiTiet().catch(() => {});
            }
        },
        onDut: () => {
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

window.addEventListener('pagehide', ngungTheoDoi);
