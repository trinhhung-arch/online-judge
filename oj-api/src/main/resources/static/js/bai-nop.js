/**
 * Lịch sử bài nộp của chính mình — FR-SUB-07, Bước G1.
 *
 * ★ MÃ ĐỀ ĐƯỢC PHÂN GIẢI THÀNH ID, KHÔNG BẮT NGƯỜI DÙNG NHẬP SỐ
 *
 * Server lọc theo `problemId`, còn thứ người ta nhớ là mã đề (`A-PLUS-B`). Trang này nhận
 * mã rồi tự hỏi `/api/v1/problems/{code}` để lấy id. Tốn một request khi bộ lọc đổi — không
 * phải mỗi trang — và đổi lại người dùng không phải đi tra một con số nội bộ.
 *
 * Mã không tồn tại thì server trả 404 và câu chữ của nó đi thẳng ra: xem mục 2 của
 * `api.js`. Ở đây chỉ thêm bối cảnh "mã đề", vì 404 trần trên trang này không nói rõ là
 * không có đề hay không có bài nộp.
 *
 * ★ TÊN NGÔN NGỮ ĐƯỢC NỐI Ở CLIENT
 *
 * `SubmissionSummaryResponse` mang `languageId` (số), `/api/v1/languages` mang `id` +
 * `displayName`. Ba dòng tham chiếu tải một lần rồi nối trong bộ nhớ — xem javadoc
 * `LanguageRepository.LanguageOption` để biết vì sao không thêm `JOIN` vào truy vấn danh
 * sách bài nộp, vốn chạy trên bảng nóng.
 *
 * Nối hỏng thì hiện `#id`, KHÔNG để trống: một ô trống nói "bài này không có ngôn ngữ",
 * còn `#3` nói "có, nhưng trang chưa tra được tên".
 */

import { goi, LoiApi } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DS, DUONG, VERDICT } from './duong-dan.js';
import { taoPhanTrang } from './phan-trang.js';

const o = khoiDong({ doiDangNhap: true });
if (o) {
    const form = document.getElementById('loc');
    const bang = document.getElementById('bang');
    const nutThem = document.getElementById('them');
    const chonVerdict = document.getElementById('verdict');
    const chonNgonNgu = document.getElementById('ngon-ngu');

    /** id ngôn ngữ -> tên hiển thị. Rỗng cho tới khi `/languages` trả về. */
    const tenNgonNgu = new Map();

    /** Kết quả phân giải mã đề gần nhất. `undefined` = không lọc theo đề. */
    let deId;

    for (const [ma, nhan] of VERDICT) {
        const opt = chu('option', nhan);
        opt.value = ma;
        chonVerdict.append(opt);
    }

    function boLoc() {
        const q = new URLSearchParams();
        if (deId !== undefined) q.set('problemId', deId);
        if (chonVerdict.value) q.set('verdict', chonVerdict.value);
        if (chonNgonNgu.value) q.set('languageId', chonNgonNgu.value);
        return q;
    }

    function veDong(b) {
        const tr = chu('tr');

        tr.append(chu('td', gio(b.createdAt)));

        const oDe = chu('td');
        const link = chu('a', b.problemCode);
        link.href = `/problem.html?code=${encodeURIComponent(b.problemCode)}`;
        link.title = b.problemTitle || '';
        oDe.append(link);
        tr.append(oDe);

        tr.append(chu('td', tenNgonNgu.get(b.languageId) || `#${b.languageId}`));

        // ★ Không dùng riêng màu để truyền tin (a11y mức A): luôn có chữ.
        // Bài chưa chấm xong không có verdict — hiện trạng thái thay vì một ô trống.
        const oKq = chu('td');
        const nhan = b.verdict || (b.status === 'DONE' ? '—' : 'đang chấm');
        const lop = b.verdict ? `verdict ${b.verdict}` : 'verdict cho';
        const nut = chu('a', nhan, lop);
        nut.href = `/submission.html?id=${encodeURIComponent(b.submissionId)}`;
        oKq.append(nut);
        tr.append(oKq);

        tr.append(chu('td', b.score === null || b.score === undefined ? '—' : b.score));

        // RuntimeFormatter đã làm tròn 10ms ở server (FR-SUB-11); đừng làm tròn lần nữa.
        tr.append(chu('td', b.timeMs === null || b.timeMs === undefined ? '—' : `${b.timeMs} ms`));

        return tr;
    }

    const trang = taoPhanTrang({
        ds: DS.baiNop,
        boLoc,
        veDong,
        vao: bang,
        nutThem,
        o,
        khiTrong: 'Chưa có bài nộp nào khớp bộ lọc.',
        loiChung: 'Không tải được danh sách bài nộp.',
    });

    /** Phân giải mã đề -> id. Ném `LoiApi` để nơi gọi hiện đúng câu của server. */
    async function phanGiaiDe(ma) {
        if (!ma) return undefined;
        const de = await goi(DUONG.de.theoMa(ma));
        return de.problemId;
    }

    form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const ma = form.maDe.value.trim();
        try {
            deId = await phanGiaiDe(ma);
        } catch (e) {
            bao(o, e instanceof LoiApi && e.status === 404
                ? `Không có đề nào mang mã “${ma}”.`
                : (e instanceof LoiApi ? e.message : 'Không tra được mã đề.'), 'loi');
            return;
        }
        trang.lamMoi();
    });

    // Danh sách ngôn ngữ là tiện nghi: hỏng thì bộ lọc mất, bảng vẫn xem được.
    // Không để một request phụ làm hỏng trang chính.
    goi(DUONG.ngonNgu).then((ds) => {
        for (const n of ds) {
            tenNgonNgu.set(n.id, n.displayName);
            const opt = chu('option', n.displayName);
            opt.value = n.id;
            chonNgonNgu.append(opt);
        }
        // Bảng có thể đã vẽ xong trước khi danh sách về — vẽ lại để thay `#id` bằng tên.
        if (bang.childElementCount) trang.lamMoi();
    }).catch(() => {
        chonNgonNgu.disabled = true;
        chonNgonNgu.insertAdjacentElement('afterend',
            chu('p', 'Không tải được danh sách ngôn ngữ; bộ lọc này tạm tắt.', 'goi-y'));
    });

    trang.lamMoi();
}
