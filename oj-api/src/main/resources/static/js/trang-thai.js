/**
 * Trang trạng thái công khai — FR-ADM-05. Bước G3.
 *
 * ★ KHÔNG ĐÒI ĐĂNG NHẬP, VÀ ĐÓ LÀ ĐIỂM CỦA TRANG
 *
 * Người gặp sự cố thường là người đang không đăng nhập được. Một trang trạng thái đòi đăng
 * nhập là một trang trạng thái vô dụng đúng lúc cần nhất.
 *
 * ★ HAI CÂU HỎI KHÁC NHAU, ĐỪNG GỘP
 *
 *   "còn nhận bài không?"  -> `dangNhanBai`, một quyết định của người vận hành (FR-ADM-06)
 *   "có máy chấm không?"   -> `mayChamSong`, một sự thật về hạ tầng
 *
 * Cả hai đều làm bài nộp không được chấm ngay, nhưng chúng đòi hai hành động khác nhau ở
 * người dùng: một cái là "quay lại sau khi bảo trì xong", cái kia là "bài của bạn đã vào
 * hàng đợi và sẽ được chấm". Gộp thành một dòng "hệ thống bận" là bỏ mất khác biệt đó.
 *
 * ★ TỰ LÀM MỚI, VÌ NGƯỜI TA MỞ TRANG NÀY RỒI ĐỂ ĐÓ
 *
 * 10 giây khớp `oj.judge.metrics-interval` — nhịp server thật sự lấy mẫu. Hỏi dày hơn chỉ
 * nhận lại cùng một con số.
 */

import { goi, LoiApi } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong } from './trang.js';
import { DUONG } from './duong-dan.js';

const NHIP_MS = 10_000;

const o = khoiDong();
const tieuDe = document.getElementById('tieu-de-nhan');
const moTa = document.getElementById('mo-ta-nhan');
const bang = document.getElementById('bang');

function giay(ms) {
    if (ms === null || ms === undefined) return '—';
    if (ms < 1000) return `${ms} ms`;
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s} giây`;
    return `${Math.floor(s / 60)} phút ${s % 60} giây`;
}

function dong(nhan, giaTri, nghia) {
    const tr = chu('tr');
    // scope="row": trình đọc màn hình đọc "Đang chờ, 0" chứ không đọc trơ một con số.
    const th = chu('th', nhan);
    th.setAttribute('scope', 'row');
    tr.append(th);
    tr.append(chu('td', giaTri));
    tr.append(chu('td', nghia, 'goi-y'));
    return tr;
}

/** Một câu tóm tắt, và nó phải nói đúng cái đang xảy ra. */
function tomTat(t) {
    if (!t.dangNhanBai) {
        return ['Đang bảo trì — tạm ngừng nhận bài nộp', 'verdict cho',
            'Bài đã nộp trước đó vẫn đang được chấm bình thường. Quay lại sau ít phút.'];
    }
    if (t.mayChamSong === 0) {
        return ['Nhận bài, nhưng chưa có máy chấm nào online', 'verdict cho',
            'Bài nộp vẫn được ghi nhận và xếp hàng — chúng sẽ được chấm ngay khi có máy chấm '
            + 'trở lại. Không bài nào bị mất.'];
    }
    return ['Hoạt động bình thường', 'verdict AC',
        `${t.mayChamSong} máy chấm đang online.`];
}

function ve(t) {
    const [nhan, lop, giaiThich] = tomTat(t);
    tieuDe.textContent = nhan;
    tieuDe.className = lop;
    moTa.textContent = giaiThich;

    bang.replaceChildren(
        dong('Đang chờ', String(t.dangCho), 'Bài đã nộp, chưa có máy chấm nào nhận'),
        dong('Đang chấm', String(t.dangCham), 'Bài đang được chấm ngay lúc này'),
        dong('Máy chấm online', String(t.mayChamSong), 'Đếm theo lần báo cáo gần nhất'),
        dong('Chờ lâu nhất', giay(t.choLauNhatMs), 'Bài đã đợi lâu nhất trong hàng'),
        dong('Chờ ước tính', giay(t.choUocTinhMs), 'Nếu bạn nộp bài ngay bây giờ'),
    );
}

async function tai() {
    try {
        ve(await goi(DUONG.trangThai));
        bao(o, '');
    } catch (e) {
        // Trang trạng thái không tải được LÀ một trạng thái, và là trạng thái đáng báo.
        tieuDe.textContent = 'Không liên lạc được với máy chủ';
        tieuDe.className = 'verdict WA';
        moTa.textContent = 'Trang này sẽ tự thử lại. Nếu kéo dài, hệ thống đang có sự cố.';
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được trạng thái.', 'loi');
    }
}

tai();
setInterval(tai, NHIP_MS);
