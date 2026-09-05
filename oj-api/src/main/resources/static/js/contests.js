/**
 * Lịch thi — FR-CON-01. Bước G4 và phần tạo của G8.
 *
 * ★ TRẠNG THÁI ĐẾN TỪ SERVER, TRANG NÀY CHỈ TÔ MÀU
 *
 * `trangThai` được suy ở `ListContestsUseCase`, không phải ở đây. Trình duyệt có đồng hồ
 * riêng và nó lệch — nhiều nhất ở đúng phút bắt đầu kỳ thi, tức là đúng lúc người ta nhìn
 * chằm chằm vào trang này. Một dòng hiện "Đang diễn ra" trong khi server còn nói chưa mở là
 * một khiếu nại không ai giải quyết được, vì hai bên đang nhìn hai đồng hồ.
 *
 * ★ KHỐI TẠO KỲ THI ẨN BẰNG `hidden` TRONG HTML, KHÔNG DỰNG BẰNG JS
 *
 * Và nó KHÔNG phải một biện pháp bảo mật: `AuthorContestUseCase` mới là nơi chặn, ở tầng
 * use-case (bất biến #11). Ẩn ở đây chỉ để người không có quyền không thấy một biểu mẫu mà
 * bấm vào sẽ nhận 403 — bày ra rồi từ chối là một cách nói dối về những gì họ làm được.
 */

import { goi, LoiApi } from './api.js';
import { chu, bao, vaiTroItNhat } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DS, DUONG, TRANG_THAI_KY_THI } from './duong-dan.js';
import { taoPhanTrang } from './phan-trang.js';

const o = khoiDong();
const bang = document.getElementById('bang');
const nutThem = document.getElementById('them');

function veDong(k) {
    const tr = chu('tr');

    const oTen = chu('td');
    const link = chu('a', k.title);
    link.href = `/contest.html?slug=${encodeURIComponent(k.slug)}`;
    oTen.append(link);
    if (k.registrationRequired) {
        oTen.append(chu('p', 'Cần đăng ký trước', 'goi-y'));
    }
    tr.append(oTen);

    tr.append(chu('td', k.format));
    tr.append(chu('td', gio(k.startsAt)));
    tr.append(chu('td', gio(k.endsAt)));

    // Không dùng riêng màu để truyền tin (a11y mức A): luôn có chữ.
    const [nhan, lop] = TRANG_THAI_KY_THI[k.trangThai] || [k.trangThai, ''];
    tr.append(chu('td', nhan, lop ? `verdict ${lop}` : ''));

    return tr;
}

const trang = taoPhanTrang({
    ds: DS.kyThi,
    boLoc: () => new URLSearchParams(),
    veDong,
    vao: bang,
    nutThem,
    o,
    khiTrong: 'Chưa có kỳ thi nào.',
    loiChung: 'Không tải được lịch thi.',
});

// ---------------------------------------------------------------------------
// G8 — tạo kỳ thi (ADMIN)
// ---------------------------------------------------------------------------

/**
 * `datetime-local` cho ra giờ ĐỊA PHƯƠNG không có múi giờ (`2026-09-04T19:00`), còn server
 * nhận `Instant` tức là UTC. Gửi thẳng chuỗi ấy là lệch đúng bằng múi giờ của người dùng —
 * ở Việt Nam là bảy tiếng, đủ để một kỳ thi mở sai buổi.
 */
function sangUtc(giaTriLocal) {
    if (!giaTriLocal) return null;
    const d = new Date(giaTriLocal);
    return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/**
 * ★ CHẶN GIỜ VÔ LÝ NGAY Ở BỘ CHỌN, đừng để người dùng biết sau khi bấm gửi.
 *
 * Server đã kiểm đủ (`AuthorContestUseCase`) và nó vẫn là chốt thật — phần dưới đây KHÔNG
 * thay thế nó, chỉ dời thời điểm phát hiện lên sớm hơn.
 *
 * Vì sao đáng làm: `datetime-local` không có `min` thì lịch của trình duyệt cho chọn bất kỳ
 * ngày nào, kể cả tuần trước. Người dùng điền xong cả form, bấm gửi, rồi nhận một dòng đỏ ở
 * ĐẦU trang — cách ô gây lỗi cả màn hình, không chỉ vào ô nào. Đo thật ngày 2026-09-05: một
 * người chọn 01/09 trong khi hôm nay là 05/09 và không hiểu vì sao bị từ chối.
 *
 * Ba ràng buộc, phản chiếu đúng ba phép kiểm của server:
 *   bắt đầu   ≥ bây giờ
 *   kết thúc  > bắt đầu
 *   đóng băng nằm trong [bắt đầu, kết thúc]
 */
function gioDiaPhuong(d) {
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function rangBuocGio() {
    const oBatDau = document.getElementById('bat-dau');
    const oKetThuc = document.getElementById('ket-thuc');
    const oDongBang = document.getElementById('dong-bang');
    if (!oBatDau) return;

    // +1 phút: chọn đúng "bây giờ" thì tới lúc bấm gửi nó đã thành quá khứ.
    oBatDau.min = gioDiaPhuong(new Date(Date.now() + 60000));

    const dongBo = () => {
        oKetThuc.min = oBatDau.value || oBatDau.min;
        oDongBang.min = oBatDau.value || oBatDau.min;
        // Chỉ đặt max khi đã có giờ kết thúc — đặt max rỗng là xoá ràng buộc, không phải
        // đặt nó thành vô hạn.
        if (oKetThuc.value) oDongBang.max = oKetThuc.value;
    };
    oBatDau.addEventListener('change', dongBo);
    oKetThuc.addEventListener('change', dongBo);
    // Sửa ô là gỡ dấu đỏ ngay. Giữ dấu đỏ trên một ô vừa được sửa đúng là nói dối người dùng.
    [oBatDau, oKetThuc, oDongBang].forEach((el) =>
        el.addEventListener('input', () => el.removeAttribute('aria-invalid')));
    dongBo();
}

// SETTER trở lên — `AuthorContestUseCase` là @RequiresRole(SETTER), không phải ADMIN.
if (vaiTroItNhat('SETTER')) {
    document.getElementById('khu-tao').hidden = false;
    rangBuocGio();
}

document.getElementById('form-tao').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const form = ev.target;
    const nut = form.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.kyThi.tao, {
            method: 'POST',
            body: {
                slug: form.slug.value.trim(),
                title: form.title.value.trim(),
                format: form.format.value,
                startsAt: sangUtc(form.startsAt.value),
                endsAt: sangUtc(form.endsAt.value),
                freezeAt: sangUtc(form.freezeAt.value),
            },
        });
        // Sang thẳng trang kỳ thi: việc kế tiếp luôn là thêm đề, và nó ở bên đó.
        location.href = `/contest.html?slug=${encodeURIComponent(form.slug.value.trim())}`;
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không tạo được kỳ thi.', 'loi');
        // Chỉ thẳng vào ô gây lỗi. Một dòng đỏ ở đầu trang không nói được ô nào sai, và
        // form này dài hơn một màn hình.
        const oLoi = { 'contest.bat_dau_trong_qua_khu': 'bat-dau',
                       'contest.khung_gio_khong_hop_le': 'ket-thuc',
                       'contest.gio_dong_bang_khong_hop_le': 'dong-bang' }[e?.code];
        if (oLoi) {
            const el = document.getElementById(oLoi);
            el.setAttribute('aria-invalid', 'true');
            el.focus();
            el.scrollIntoView({ block: 'center' });
        }
        nut.disabled = false;
    }
});

trang.lamMoi();
