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
import { chu, bao, vaiTroItNhat } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DUONG, DS } from './duong-dan.js';
import { gan as ganBangXepHang } from './bang-xep-hang.js';

const o = khoiDong();
const slug = new URLSearchParams(location.search).get('slug');

const tieuDe = document.getElementById('tieu-de');
const tomTat = document.getElementById('tom-tat');
const bangDe = document.getElementById('bang-de');
const viSaoTrong = document.getElementById('vi-sao-trong');
const nutDangKy = document.getElementById('dang-ky');
const khuRaDe = document.getElementById('khu-ra-de');
const khuCongBo = document.getElementById('khu-cong-bo');
const dsMaDe = document.getElementById('ds-ma-de');

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

    // Hai mức quyền khác nhau, hai lần hỏi khác nhau — xem chú thích ở contest.html.
    khuRaDe.hidden = !vaiTroItNhat('SETTER');
    khuCongBo.hidden = !vaiTroItNhat('ADMIN');
    if (!khuRaDe.hidden) goiYMaDe();

    ganBangXepHang(kyThi.id, { o });
}

// ---------------------------------------------------------------------------

/**
 * Đổ <datalist> gợi ý mã đề — TIỆN, không phải NGUỒN SỰ THẬT.
 *
 * Chỉ lấy trang đầu. Một kho đề lớn sẽ không nằm hết ở đây, và đó là lý do ô nhập vẫn là
 * <input> tự do chứ không phải <select>: gõ một mã không có trong gợi ý vẫn phải thêm được.
 * Một <select> đổ từ trang đầu thì đề thứ 101 biến mất mà không ai được báo.
 *
 * Lỗi ở đây nuốt im lặng: mất gợi ý là mất tiện nghi, không mất chức năng, và một dòng đỏ
 * cho việc đó chỉ làm người ta tưởng form đang hỏng.
 */
async function goiYMaDe() {
    try {
        const trang = await goi(`${DS.de.url}?${DS.de.khoaSize}=100`);
        dsMaDe.replaceChildren();
        for (const de of trang.items) {
            // `muc`, không phải `o`: `o` là ô thông báo của cả trang, và che nó ở đây là
            // thứ chạy đúng hôm nay rồi hỏng vào ngày ai đó thêm một dòng bao() vào vòng lặp.
            const muc = chu('option');
            muc.value = de.code;
            muc.label = de.title;
            dsMaDe.append(muc);
        }
    } catch {
        // Không sao — ô nhập vẫn dùng tay được.
    }
}

/**
 * Mã đề (thứ người dùng nhìn thấy) -> problemId (thứ API nhận).
 *
 * Một request thêm, cố ý. Đây là thao tác của người ra đề, không nằm trên đường nóng
 * `nộp bài -> verdict`, nên đổi 1 request lấy một thông báo lỗi đúng là đổi có lãi.
 *
 * 404 ở đây gộp ba nguyên nhân — không có mã ấy, đề còn DRAFT, hoặc đề đang bị một kỳ thi
 * khác khoá. Không tách ra được từ phía client, và cũng không NÊN tách: `GetProblemUseCase`
 * cố ý trả cùng một câu cho cả ba để đề của kỳ thi sắp mở không bị dò ra bằng cách so mã lỗi.
 */
async function doiMaDeRaId(maDe) {
    try {
        const de = await goi(DUONG.de.theoMa(maDe));
        return de.problemId;
    } catch (e) {
        if (e instanceof LoiApi && e.status === 404) {
            // Vẫn là LoiApi — nó THẬT SỰ là lỗi từ API, chỉ được nói lại bằng câu có ích
            // hơn "Không tìm thấy đề này." Giữ đúng kiểu để nhánh catch ở form không phải
            // nới ra bắt Error trần, thứ sẽ hiện cả TypeError của một lỗi lập trình ra
            // trước mặt người dùng.
            throw new LoiApi(404, 'contest.ma_de_khong_ro',
                `Không tìm thấy đề có mã “${maDe}”. `
                + 'Kiểm lại cột “Mã đề” ở trang Đề bài — và đề phải đã xuất bản.');
        }
        throw e;
    }
}

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
        const problemId = await doiMaDeRaId(form.elements.code.value.trim());
        await goi(DUONG.kyThi.themDe(kyThi.id), {
            method: 'POST',
            body: {
                problemId,
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
