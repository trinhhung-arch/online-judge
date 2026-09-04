/**
 * Nạp và tải bộ test — FR-PROB-03, 04, 10, 12. Bước G10.
 *
 * ★ TẢI VỀ KHÔNG DÙNG ĐƯỢC MỘT THẺ `<a href>`
 *
 * Token nằm trong header `Authorization`, cố ý — `sse.js` đã ghi lý do: query string đi vào
 * log truy cập của mọi proxy trên đường. Một thẻ `<a>` thì trình duyệt tự đi lấy và không
 * kèm header nào, nên nó nhận 401. Phải `fetch` rồi dựng blob.
 *
 * ★ NẠP LÊN LÀ MỘT VIỆC NỀN, KHÔNG PHẢI MỘT REQUEST
 *
 * Máy chủ trả `202` kèm `jobId` rồi giải nén ở nền (`CLAUDE.md` mục 4 câu hỏi 4: quá 5 giây
 * thì phải là job có tiến độ). Nên nút này KHÔNG chờ xong — nó bàn giao cho `tien-do-job.js`.
 *
 * ★ RỜI TRANG KHÔNG HUỶ VIỆC, VÀ CÂU CHỮ PHẢI NÓI ĐÚNG THẾ
 *
 * Người tưởng việc đã hỏng sẽ nạp lại lần nữa, và hai lần nạp cùng một đề là hai phiên bản
 * testdata. Mọi thông báo ở đây nói rõ việc vẫn sống ở máy chủ.
 */

import { goi, LoiApi, accessToken } from './api.js';
import { bao } from './khung.js';
import { DUONG } from './duong-dan.js';
import { theoDoi } from './tien-do-job.js';

const form = document.getElementById('form-testdata');
const nutNap = document.getElementById('nap');
const nutTaiVe = document.getElementById('tai-ve');
const oTienDo = document.getElementById('tien-do');
const oTep = document.getElementById('tep');

/** Đề đang mở. `ganTestdata` gọi lại mỗi lần mở đề khác, nên trạng thái nằm ở module. */
let deHienTai = null;
let dangTheoDoi = null;
let daNoiDay = false;

/**
 * `attachment; filename="a.zip"` → `a.zip`. Sai thì trả tên mặc định: một file tải về không
 * có tên còn tệ hơn một file có tên chung chung.
 */
function tenTuHeader(header, macDinh) {
    const m = /filename="?([^"]+)"?/.exec(header || '');
    return m ? m[1] : macDinh;
}

async function nap(ev) {
    ev.preventDefault();
    if (!deHienTai) return;

    const tep = oTep.files?.[0];
    if (!tep) {
        bao(o(), 'Chưa chọn gói testdata.', 'loi');
        return;
    }

    nutNap.disabled = true;
    bao(o(), '');
    try {
        const than = new FormData();
        than.append('file', tep);
        const kq = await goi(DUONG.de.testdata(deHienTai), { method: 'POST', body: than });

        bao(o(), `Đã nhận gói. Việc #${kq.jobId} đang chạy ở máy chủ — rời trang cũng không `
            + 'dừng nó.', 'on');

        dangTheoDoi?.dung();
        dangTheoDoi = theoDoi(kq.jobId, {
            vao: oTienDo,
            khiXong: (job) => {
                if (job.status === 'DONE') {
                    bao(o(), 'Nạp bộ test xong.', 'on');
                    form.reset();
                } else {
                    bao(o(), `Việc #${job.id} kết thúc với trạng thái ${job.status}.`, 'loi');
                }
            },
        });
    } catch (e) {
        bao(o(), e instanceof LoiApi ? e.message : 'Không nạp được gói testdata.', 'loi');
    } finally {
        nutNap.disabled = false;
    }
}

async function taiVe() {
    if (!deHienTai) return;
    nutTaiVe.disabled = true;
    bao(o(), '');
    try {
        const res = await fetch(DUONG.de.testdata(deHienTai), {
            headers: accessToken() ? { Authorization: `Bearer ${accessToken()}` } : {},
        });
        if (!res.ok) {
            // Thân lỗi ở đây là JSON như mọi endpoint khác, nhưng đường thành công là nhị
            // phân — nên không dùng chung `goi()` được, và phải tự đọc mã lỗi.
            let thongDiep = 'Không tải được bộ test.';
            try {
                thongDiep = (await res.json())?.message || thongDiep;
            } catch {
                // Không phải JSON. Giữ câu mặc định.
            }
            bao(o(), thongDiep, 'loi');
            return;
        }

        const blob = await res.blob();
        const ten = tenTuHeader(res.headers.get('Content-Disposition'),
            `testdata-${deHienTai}.zip`);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = ten;
        document.body.append(a);
        a.click();
        a.remove();
        // Không thu hồi ngay: vài trình duyệt còn đang đọc blob lúc `click()` vừa trả về.
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        bao(o(), `Đang tải ${ten}.`, 'on');
    } catch {
        bao(o(), 'Không tải được bộ test. Kiểm tra kết nối rồi thử lại.', 'loi');
    } finally {
        nutTaiVe.disabled = false;
    }
}

let vungBao = null;
function o() {
    return vungBao;
}

/**
 * Nối khu vực testdata vào một đề. Gọi lại được: đổi đề thì chỉ đổi mục tiêu, không gắn
 * thêm một bộ listener nữa — gắn chồng là mỗi lần bấm gửi nhiều request.
 */
export function ganTestdata(problemId, { o: vung }) {
    deHienTai = problemId;
    vungBao = vung;

    dangTheoDoi?.dung();
    dangTheoDoi = null;
    oTienDo.replaceChildren();

    if (daNoiDay) return;
    daNoiDay = true;

    form.addEventListener('submit', nap);
    nutTaiVe.addEventListener('click', taiVe);
    window.addEventListener('pagehide', () => dangTheoDoi?.dung());
}
