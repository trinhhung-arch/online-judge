/**
 * Vận hành — FR-ADM-01, 03, 04, 06 và FR-SUB-09. Bước G11.
 *
 * ★ MỌI THAO TÁC Ở ĐÂY ĐỀU ĐI VÀO `audit_log`, VÀ ĐÓ LÀ LÝ DO CÓ TRANG NÀY
 *
 * Cùng những việc này làm bằng `psql` thì không ai ghi lại. Một hệ thống bán sự công bằng
 * mà quyền admin dùng được không dấu vết là một hệ thống không chứng minh được điều nó bán.
 * Nên trang này không phải để tiện — nó là để mọi lần dùng quyền đều có tên và có giờ.
 *
 * ★ VIỆC MỘT CHIỀU THÌ HỎI LẠI, VIỆC HOÀN TÁC ĐƯỢC THÌ KHÔNG
 *
 * Ẩn bài nộp hoàn tác được bằng nút ngay cạnh, nên không hỏi. Ẩn danh hoá thì không, nên
 * hỏi — và hỏi bằng cách bắt gõ lại mã số, vì một hộp thoại "Bạn chắc chứ?" là thứ người ta
 * bấm Đồng ý theo phản xạ.
 *
 * ★ BẢNG SỐ KHÔNG TỰ LÀM MỚI
 *
 * Một trang vận hành tự poll là một trang chạy suốt đêm trên màn hình bỏ quên, gửi request
 * mãi. Người vận hành bấm khi cần; con số luôn kèm giờ đọc được để không ai nhìn số cũ mà
 * tưởng là số bây giờ.
 */

import { goi, LoiApi, phien } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DUONG } from './duong-dan.js';
import { theoDoi } from './tien-do-job.js';

const o = khoiDong({ doiDangNhap: true });

const bangSo = document.getElementById('bang-so');
const capNhatLuc = document.getElementById('cap-nhat-luc');
const oTienDo = document.getElementById('tien-do');
const oBaoTri = document.getElementById('trang-thai-bao-tri');
const oCongTac = document.getElementById('cong-tac');

let dangTheoDoi = null;

/** Nhãn và cách đọc từng số. Thứ tự ở đây là thứ tự hiển thị. */
const SO_LIEU = [
    ['hangDoiDangCho', 'Đang chờ chấm', (v) => v],
    ['hangDoiDangCham', 'Đang chấm', (v) => v],
    ['rejudgeDangCho', 'Chấm lại đang chờ', (v) => v],
    ['choLauNhatMs', 'Chờ lâu nhất', (v) => `${(v / 1000).toFixed(1)} giây`],
    ['mayChamSong', 'Máy chấm sống', (v) => v],
    ['baiDaCham', 'Tổng bài đã chấm', (v) => v],
    ['tiLeIe', 'Tỉ lệ IE', (v) => `${(v * 100).toFixed(2)}%`],
    ['drift', 'Lệch bảng xếp hạng', (v) => v],
    ['apiP95Ms', 'API p95', (v) => `${Math.round(v)} ms`],
];

// ---------------------------------------------------------------------------

async function taiBangSo() {
    bao(o, '');
    try {
        const so = await goi(DUONG.quanTri.bangDieuKhien);
        bangSo.replaceChildren();
        for (const [khoa, nhan, doc] of SO_LIEU) {
            const v = so[khoa];
            bangSo.append(chu('dt', nhan));
            bangSo.append(chu('dd', v === null || v === undefined ? '—' : doc(v)));
        }
        capNhatLuc.textContent = `Số liệu lúc ${gio(new Date().toISOString())}.`;
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không đọc được bảng điều khiển.', 'loi');
    }
}

async function taiBaoTri() {
    try {
        const tt = await goi(DUONG.trangThai);
        oBaoTri.className = tt.dangNhanBai ? 'thong-bao on' : 'thong-bao loi';
        oBaoTri.textContent = tt.dangNhanBai
            ? 'Đang nhận bài bình thường.'
            : 'ĐANG BẢO TRÌ — máy chủ từ chối bài nộp mới. Bài đang chấm vẫn chấm xong.';
    } catch {
        oBaoTri.className = 'thong-bao';
        oBaoTri.textContent = 'Không đọc được trạng thái nhận bài.';
    }
}

/**
 * ★ Câu hỏi xác nhận chỉ dành cho chiều TẮT.
 *
 * Bật lại một công tắc là hoàn tác; tắt nó là dừng một phần hệ thống cho tất cả mọi người.
 * Hỏi cả hai chiều là dạy người ta bấm Đồng ý mà không đọc, và khi đó câu hỏi ở chiều nguy
 * hiểm cũng mất tác dụng.
 */
function duocPhepDoi(ct, batMoi) {
    if (batMoi) return true;
    return confirm(`Tắt "${ct.moTa}"\n\nĐiều này ảnh hưởng tới mọi người dùng ngay lập tức. `
        + 'Tiếp tục?');
}

async function doiCongTac(ct, batMoi) {
    if (!duocPhepDoi(ct, batMoi)) return;
    bao(o, '');
    try {
        await goi(DUONG.quanTri.datCongTac(ct.khoa), {
            method: 'POST',
            body: { bat: batMoi },
        });
        bao(o, `Đã ${batMoi ? 'bật' : 'tắt'}: ${ct.khoa}.`, batMoi ? 'on' : 'loi');
        await Promise.all([taiCongTac(), taiBaoTri()]);
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không đổi được công tắc.', 'loi');
    }
}

function veCongTac(ct) {
    const khoi = chu('div', null, 'the');

    const tieu = chu('p');
    tieu.append(chu('code', ct.khoa));
    tieu.append(chu('span', ct.bat ? ' · ĐANG BẬT' : ' · ĐANG TẮT',
        ct.bat ? 'verdict AC' : 'verdict WA'));
    khoi.append(tieu);

    // Câu mô tả đến từ máy chủ — xem javadoc của ToggleSystemSwitchUseCase.
    khoi.append(chu('p', ct.moTa, 'goi-y'));

    // Một nút, và nó nói ra việc nó sắp làm chứ không nói trạng thái hiện tại. "Tắt nhận bài"
    // rõ hơn một ô đánh dấu, vì ô đánh dấu bắt người ta suy ra hậu quả từ vị trí của nó.
    const nut = chu('button', ct.bat ? 'Tắt' : 'Bật', ct.bat ? 'nguy-hiem' : '');
    nut.type = 'button';
    nut.addEventListener('click', () => doiCongTac(ct, !ct.bat));
    khoi.append(nut);

    return khoi;
}

async function taiCongTac() {
    try {
        const ds = await goi(DUONG.quanTri.congTac);
        oCongTac.replaceChildren(...ds.map(veCongTac));
    } catch (e) {
        oCongTac.replaceChildren(chu('p',
            e instanceof LoiApi ? e.message : 'Không đọc được danh sách công tắc.', 'loi'));
    }
}

/** Gọi một endpoint không trả gì, rồi báo một câu. Dùng cho tám nút giống nhau ở dưới. */
async function bam(duongDan, than, xong) {
    bao(o, '');
    try {
        await goi(duongDan, { method: 'POST', ...(than ? { body: than } : {}) });
        bao(o, xong, 'on');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Thao tác không thành công.', 'loi');
    }
}

function soNguoiDung() {
    const v = Number(document.getElementById('nguoi-dung-id').value);
    if (!v) {
        bao(o, 'Chưa nhập mã số người dùng.', 'loi');
        return null;
    }
    return v;
}

// ---------------------------------------------------------------------------

if (o) {
    if (phien()?.role !== 'ADMIN') {
        // Không phải chốt bảo mật — mỗi use-case tự kiểm (bất biến #11). Nói trước cho đỡ mất công.
        bao(o, 'Trang này dành cho ADMIN. Máy chủ sẽ từ chối mọi thao tác ở đây.', 'loi');
    }

    document.getElementById('lam-moi').addEventListener('click', taiBangSo);

    document.getElementById('form-cham-lai').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const id = Number(ev.target.problemId.value);
        bao(o, '');
        try {
            const kq = await goi(DUONG.quanTri.chamLai(id), { method: 'POST' });
            bao(o, `Đã tạo việc #${kq.jobId}. Nó chạy ở nền, rời trang cũng không dừng.`, 'on');
            dangTheoDoi?.dung();
            dangTheoDoi = theoDoi(kq.jobId, {
                vao: oTienDo,
                khiXong: (job) => bao(o,
                    `Việc #${job.id} kết thúc: ${job.status}.`,
                    job.status === 'DONE' ? 'on' : 'loi'),
            });
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : 'Không bắt đầu được việc chấm lại.', 'loi');
        }
    });

    document.getElementById('form-bai-nop').addEventListener('submit', (ev) => {
        ev.preventDefault();
        const id = Number(ev.target.submissionId.value);
        // `submitter` cho biết nút nào được bấm; không có nó thì hai nút thành một.
        const an = ev.submitter?.value !== 'hien';
        bam(an ? DUONG.quanTri.anBai(id) : DUONG.quanTri.hienBai(id), null,
            an ? `Đã ẩn bài nộp #${id}.` : `Đã hiện lại bài nộp #${id}.`);
    });

    document.getElementById('doi-vai-tro').addEventListener('click', () => {
        const id = soNguoiDung();
        if (!id) return;
        const vaiTro = document.getElementById('vai-tro').value;
        bam(DUONG.quanTri.vaiTro(id), { vaiTro }, `Đã đặt #${id} thành ${vaiTro}.`);
    });

    document.getElementById('mo-khoa').addEventListener('click', () => {
        const id = soNguoiDung();
        if (id) bam(DUONG.quanTri.hoatDong(id), { hoatDong: true }, `#${id} đã hoạt động lại.`);
    });

    document.getElementById('khoa').addEventListener('click', () => {
        const id = soNguoiDung();
        if (id) bam(DUONG.quanTri.hoatDong(id), { hoatDong: false }, `#${id} đã bị vô hiệu hoá.`);
    });

    document.getElementById('an-danh').addEventListener('click', () => {
        const id = soNguoiDung();
        if (!id) return;
        // Gõ lại mã số, không phải bấm Đồng ý: hộp thoại xác nhận là thứ người ta bấm qua
        // theo phản xạ, còn gõ lại một con số thì buộc phải đọc con số ấy.
        const traLoi = prompt(`Ẩn danh hoá KHÔNG hoàn tác được.\n`
            + `Gõ lại mã số ${id} để xác nhận:`);
        if (traLoi === null) return;
        if (traLoi.trim() !== String(id)) {
            bao(o, 'Mã số không khớp. Không làm gì cả.', 'loi');
            return;
        }
        bam(DUONG.quanTri.anDanh(id), null, `Đã ẩn danh hoá tài khoản #${id}.`);
    });

    window.addEventListener('pagehide', () => dangTheoDoi?.dung());

    taiBangSo();
    taiBaoTri();
    taiCongTac();
}
