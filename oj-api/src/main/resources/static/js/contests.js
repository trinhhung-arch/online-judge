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

// SETTER trở lên — `AuthorContestUseCase` là @RequiresRole(SETTER), không phải ADMIN.
if (vaiTroItNhat('SETTER')) {
    document.getElementById('khu-tao').hidden = false;
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
        nut.disabled = false;
    }
});

trang.lamMoi();
