/** Trang đề bài: xem đề, soạn mã, nộp — FR-PROB-02, FR-SUB-01/08/10. Bước 4.12. */

import { goi, LoiApi } from './api.js';
import { veThanh, bao, chu, thamSo, canDangNhap } from './khung.js';
import { gan } from './editor.js';
import * as nhap from './nhap.js';
import { DUONG } from './duong-dan.js';

veThanh();

const o = document.getElementById('thong-bao');
const chonNgonNgu = document.getElementById('ngon-ngu');
const nutNop = document.getElementById('nop');
const demNguoc = document.getElementById('dem-nguoc');
const code = thamSo('code');

let de = null;
let soan = null;

async function taiDe() {
    de = await goi(DUONG.de.theoMa(code));
    document.title = `${de.code} · ${de.title}`;
    document.getElementById('tieu-de').textContent = `${de.code} · ${de.title}`;
    document.getElementById('gioi-han').textContent =
        `Giới hạn: ${de.timeLimitMs} ms · ${Math.round(de.memoryLimitKb / 1024)} MB`;

    // ★ innerHTML — chỗ DUY NHẤT của cả giao diện. An toàn vì server đã render bằng
    // CommonMark với escapeHtml(true): mọi thẻ HTML trong đề đã thành văn bản.
    const khung = document.getElementById('de-bai');
    khung.innerHTML = de.statementHtml;

    // KaTeX vẽ công thức SAU khi HTML đã vào DOM. Nếu CDN chết thì công thức hiện dưới
    // dạng $...$ — đọc được, chỉ xấu. Trang không vỡ.
    if (window.renderMathInElement) {
        window.renderMathInElement(khung, {
            delimiters: [
                { left: '$$', right: '$$', display: true },
                { left: '$', right: '$', display: false },
            ],
            throwOnError: false,
        });
    }
}

/**
 * ★ Bước G3 — chế độ bảo trì (FR-ADM-06) phải thấy được TRƯỚC khi bấm.
 *
 * Khi người vận hành tắt nhận bài, `SubmitSolutionUseCase` từ chối. Để người dùng gõ xong
 * một lời giải rồi mới biết là đổi một phút của họ lấy một thông báo lỗi — và trên trang
 * này thì thứ họ vừa gõ là thứ họ quan tâm nhất.
 *
 * Best-effort có chủ ý: `/status` hỏng thì KHÔNG tắt nút. Một trang trạng thái chập chờn
 * không được phép chặn đường nộp bài — server vẫn là nơi quyết định, và nó từ chối đúng.
 */
async function kiemBaoTri() {
    try {
        const t = await goi(DUONG.trangThai);
        if (t.dangNhanBai) return;

        nutNop.disabled = true;
        demNguoc.textContent = 'Hệ thống đang bảo trì, tạm ngừng nhận bài nộp. '
            + 'Mã nguồn của bạn vẫn được giữ trong trình duyệt.';
    } catch {
        // Im lặng: xem javadoc trên.
    }
}

async function taiNgonNgu() {
    const ds = await goi(DUONG.ngonNgu);
    for (const l of ds) {
        const opt = chu('option', `${l.displayName} — ${l.versionLabel}`);
        opt.value = l.code;
        chonNgonNgu.append(opt);
    }
    if (!ds.length) {
        bao(o, 'Hệ thống chưa bật ngôn ngữ chấm nào.', 'loi');
        nutNop.disabled = true;
    }
}

const luuNhap = nhap.hoanLai((ma) => {
    nhap.luu(de.code, chonNgonNgu.value, ma);
    document.getElementById('trang-thai-nhap').textContent =
        'Nháp đã lưu trong trình duyệt của bạn.';
});

async function dungTrinhSoan() {
    soan = await gan(
        document.getElementById('khung-soan'),
        document.getElementById('ma-nguon'),
        { onThayDoi: luuNhap, ngonNguBanDau: chonNgonNgu.value });
    soan.dat(nhap.doc(de.code, chonNgonNgu.value));

    chonNgonNgu.addEventListener('change', () => {
        // Nháp khoá theo (đề, ngôn ngữ): đổi ngôn ngữ không được xoá bản C++ đang viết dở.
        soan.doiNgonNgu(chonNgonNgu.value);
        soan.dat(nhap.doc(de.code, chonNgonNgu.value));
    });
}

/** FR-SUB-08 — giới hạn là quy tắc được CÔNG BỐ, nên nó phải hiện ra thành đếm ngược. */
function demNguocLai(giay) {
    nutNop.disabled = true;
    let conLai = giay;
    const nhip = setInterval(() => {
        demNguoc.textContent = conLai > 0 ? `Nộp lại được sau ${conLai} giây.` : '';
        if (conLai-- <= 0) {
            clearInterval(nhip);
            nutNop.disabled = false;
        }
    }, 1000);
    demNguoc.textContent = `Nộp lại được sau ${conLai} giây.`;
}

async function nop() {
    if (!canDangNhap()) return;
    const ma = soan.doc();
    if (!ma.trim()) {
        bao(o, 'Chưa có mã nguồn nào để nộp.', 'loi');
        return;
    }
    nutNop.disabled = true;
    bao(o, 'Đang gửi…');
    try {
        const kq = await goi(DUONG.baiNop.nop, {
            method: 'POST',
            body: { problemId: de.problemId, languageCode: chonNgonNgu.value, source: ma },
        });
        location.href = `/submission.html?id=${kq.submissionId}`;
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không gửi được bài nộp.', 'loi');
        if (e instanceof LoiApi && e.status === 429) {
            demNguocLai(Number(e.retryAfter) || 10);
        } else {
            nutNop.disabled = false;
        }
    }
}

nutNop.addEventListener('click', nop);

(async () => {
    if (!code) {
        bao(o, 'Thiếu mã đề trên đường dẫn.', 'loi');
        return;
    }
    try {
        await Promise.all([taiDe(), taiNgonNgu()]);
        await dungTrinhSoan();
        // SAU trình soạn: nháp phải được khôi phục xong trước khi ta có thể hứa
        // "mã nguồn của bạn vẫn được giữ".
        await kiemBaoTri();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được đề bài.', 'loi');
    }
})();
