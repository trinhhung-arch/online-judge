/**
 * Chi tiết kỳ thi: đăng ký, danh sách đề, điều hành — FR-CON-02, 03, 07. Bước G5 và G8.
 *
 * ★ DANH SÁCH ĐỀ RỖNG CÓ HAI NGHĨA, VÀ TRANG PHẢI NÓI ĐÚNG NGHĨA NÀO
 *
 * `cacDe` rỗng khi kỳ thi chưa mở — server giấu nó, vì chính danh sách mã đề đã là thông tin
 * (`GetContestUseCase`). Nhưng nó cũng rỗng khi kỳ thi thật sự chưa có đề nào.
 *
 * Phân biệt bằng `trangThai`, thứ server suy và gửi kèm. KHÔNG so `startsAt` với đồng hồ của
 * trình duyệt: đồng hồ ấy lệch, và nó lệch ở đúng phút bắt đầu — tức là đúng lúc cả phòng
 * thi đang nhìn màn hình.
 *
 * ★ NÚT ĐĂNG KÝ ẨN KHI KHÔNG DÙNG ĐƯỢC, KHÔNG PHẢI TẮT RỒI ĐỂ ĐÓ
 *
 * Kỳ thi đã bắt đầu thì không đăng ký được nữa (FR-CON-02). Một nút xám vẫn nằm đó mời người
 * ta bấm và nhận lỗi; không có nút thì câu chuyện đã rõ. Còn khi đã đăng ký rồi thì hiện một
 * câu khẳng định — im lặng làm người ta bấm lại để chắc.
 */

import { goi, LoiApi, phien } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DUONG } from './duong-dan.js';
import { gan as ganBangXepHang } from './bang-xep-hang.js';

const o = khoiDong();
const slug = new URLSearchParams(location.search).get('slug');

const tieuDe = document.getElementById('tieu-de');
const tomTat = document.getElementById('tom-tat');
const bangDe = document.getElementById('bang-de');
const viSaoTrong = document.getElementById('vi-sao-trong');
const nutDangKy = document.getElementById('dang-ky');
const khuAdmin = document.getElementById('khu-admin');

const NHAN_TRANG_THAI = {
    SAP_DIEN_RA: 'Sắp diễn ra',
    DANG_CHAY: 'Đang diễn ra',
    DA_KET_THUC: 'Đã kết thúc',
};

let kyThi = null;

function veDe(d) {
    const tr = chu('tr');
    tr.append(chu('td', d.label));

    // Trong lúc thi, bảng này LÀ thanh điều hướng của thí sinh — nên mỗi dòng phải bấm được.
    const oMa = chu('td');
    const link = chu('a', d.code);
    link.href = `/problem.html?code=${encodeURIComponent(d.code)}`;
    oMa.append(link);
    tr.append(oMa);

    tr.append(chu('td', d.points));
    return tr;
}

function veDanhSachDe() {
    bangDe.replaceChildren(...kyThi.cacDe.map(veDe));

    if (kyThi.cacDe.length) {
        viSaoTrong.hidden = true;
        return;
    }
    viSaoTrong.hidden = false;
    viSaoTrong.className = 'thong-bao';
    viSaoTrong.textContent = kyThi.trangThai === 'SAP_DIEN_RA'
        ? 'Đề bài được mở đúng giờ bắt đầu. Ngay cả số lượng đề cũng chưa được công bố.'
        : 'Kỳ thi này chưa có đề nào.';
}

function veDangKy() {
    if (kyThi.daDangKy) {
        nutDangKy.hidden = true;
        tomTat.append(chu('span', ' · Bạn đã đăng ký.'));
        return;
    }
    // Chỉ hiện nút khi thật sự đăng ký được: phải đăng nhập, và chưa tới giờ bắt đầu.
    nutDangKy.hidden = !(phien() && kyThi.trangThai === 'SAP_DIEN_RA');
}

async function tai() {
    kyThi = await goi(DUONG.kyThi.theoSlug(slug));

    document.title = `${kyThi.title} · Online Judge`;
    tieuDe.textContent = kyThi.title;
    tomTat.replaceChildren(chu('span',
        `${kyThi.format} · ${NHAN_TRANG_THAI[kyThi.trangThai] || kyThi.trangThai}`
        + ` · ${gio(kyThi.startsAt)} → ${gio(kyThi.endsAt)}`));

    veDanhSachDe();
    veDangKy();

    if (phien()?.role === 'ADMIN') {
        khuAdmin.hidden = false;
    }

    ganBangXepHang(kyThi.id, { o });
}

// ---------------------------------------------------------------------------

nutDangKy.addEventListener('click', async () => {
    nutDangKy.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.kyThi.dangKy(kyThi.id), { method: 'POST' });
        bao(o, 'Đã đăng ký. Hẹn gặp bạn lúc bắt đầu.', 'on');
        nutDangKy.hidden = true;
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không đăng ký được.', 'loi');
        nutDangKy.disabled = false;
    }
});

document.getElementById('form-them-de').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const form = ev.target;
    const nut = form.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.kyThi.themDe(kyThi.id), {
            method: 'POST',
            body: {
                problemId: Number(form.problemId.value),
                label: form.label.value.trim(),
                ordinal: Number(form.ordinal.value),
                points: Number(form.points.value),
            },
        });
        bao(o, 'Đã thêm đề.', 'on');
        await tai();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không thêm được đề.', 'loi');
    } finally {
        nut.disabled = false;
    }
});

document.getElementById('cong-bo').addEventListener('click', async () => {
    // Công bố là một chiều: nó mở bảng đã đóng băng cho mọi người và không hoàn tác được.
    if (!confirm('Công bố bảng xếp hạng đầy đủ cho mọi người? Không hoàn tác được.')) {
        return;
    }
    bao(o, '');
    try {
        await goi(DUONG.kyThi.congBo(kyThi.id), { method: 'POST' });
        bao(o, 'Đã công bố bảng xếp hạng.', 'on');
        await tai();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không công bố được.', 'loi');
    }
});

if (!slug) {
    bao(o, 'Thiếu tham số kỳ thi trên đường dẫn.', 'loi');
} else {
    tai().catch((e) => {
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được kỳ thi.', 'loi');
        tieuDe.textContent = 'Không mở được kỳ thi';
    });
}
