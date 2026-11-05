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
    const th = chu('th', nhan);
    th.setAttribute('scope', 'row');
    tr.append(th);
    tr.append(chu('td', giaTri));
    tr.append(chu('td', nghia, 'goi-y'));
    return tr;
}

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
        tieuDe.textContent = 'Không liên lạc được với máy chủ';
        tieuDe.className = 'verdict WA';
        moTa.textContent = 'Trang này sẽ tự thử lại. Nếu kéo dài, hệ thống đang có sự cố.';
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được trạng thái.', 'loi');
    }
}

tai();
setInterval(tai, NHIP_MS);
