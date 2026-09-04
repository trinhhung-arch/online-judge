/**
 * Nhật ký kiểm toán — FR-ADM-02. Bước G12.
 *
 * ★ `chiTiet` LÀ DỮ LIỆU TỰ DO, NÊN NÓ CHỈ ĐƯỢC LÀ CHỮ
 *
 * Máy chủ trả một `Map<String, Object>` mà nội dung do chỗ ghi log quyết định. Đưa nó vào
 * `innerHTML` là mở một đường XSS đi qua chính bảng dùng để điều tra sự cố — và người đọc
 * bảng ấy luôn là ADMIN. `chu()` đặt `textContent`, nên mọi thứ ở đây là chữ, kể cả khi nó
 * trông giống thẻ HTML.
 *
 * ★ HAI Ô THỜI GIAN LÀ GIỜ ĐỊA PHƯƠNG, MÁY CHỦ NHẬN UTC
 *
 * Cùng cái bẫy đã ghi ở `contests.js`: `datetime-local` cho ra `2026-09-04T19:00` không kèm
 * múi giờ. Gửi thẳng là lệch bảy tiếng ở Việt Nam — và ở một bảng dùng để trả lời "lúc ấy ai
 * làm gì", lệch bảy tiếng nghĩa là tìm nhầm người.
 */

import { chu } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DS } from './duong-dan.js';
import { taoPhanTrang } from './phan-trang.js';

const o = khoiDong({ doiDangNhap: true });

const bang = document.getElementById('bang');
const nutThem = document.getElementById('them');
const formLoc = document.getElementById('loc');

function sangUtc(giaTriLocal) {
    if (!giaTriLocal) return null;
    const d = new Date(giaTriLocal);
    return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

function doiTuong(d) {
    if (!d.loaiThucThe) return '—';
    return d.idThucThe === null || d.idThucThe === undefined
        ? d.loaiThucThe
        : `${d.loaiThucThe} #${d.idThucThe}`;
}

function chiTiet(d) {
    const c = d.chiTiet;
    if (!c || !Object.keys(c).length) return '—';
    try {
        return JSON.stringify(c);
    } catch {
        // Vòng tham chiếu thì thôi, đừng làm hỏng cả bảng vì một dòng.
        return '(không đọc được)';
    }
}

function veDong(d) {
    const tr = chu('tr');
    tr.append(chu('td', gio(d.luc)));

    const oNguoi = chu('td');
    oNguoi.append(chu('span', d.nguoiThucHien));
    if (d.nguoiThucHienId !== null && d.nguoiThucHienId !== undefined) {
        oNguoi.append(chu('span', ` #${d.nguoiThucHienId}`, 'goi-y'));
    }
    if (d.vaiTro) {
        oNguoi.append(chu('span', ` · ${d.vaiTro}`, 'goi-y'));
    }
    tr.append(oNguoi);

    tr.append(chu('td', d.hanhDong));
    tr.append(chu('td', doiTuong(d)));

    const oChiTiet = chu('td');
    oChiTiet.append(chu('code', chiTiet(d)));
    tr.append(oChiTiet);

    tr.append(chu('td', d.ip || '—'));
    return tr;
}

function boLoc() {
    const q = new URLSearchParams();
    // ★ `formLoc.action` là thuộc tính có sẵn của <form> (URL gửi đi). Trình duyệt cho
    // control trùng tên thắng, nhưng dựa vào luật ấy là viết một dòng mà người đọc sau phải
    // tra spec mới biết là đúng. `elements.` nói thẳng ý định.
    const action = formLoc.elements.action.value.trim();
    if (action) q.set('action', action);

    const actorId = formLoc.actorId.value.trim();
    if (actorId) q.set('actorId', actorId);

    const tu = sangUtc(formLoc.tu.value);
    if (tu) q.set('tu', tu);

    const den = sangUtc(formLoc.den.value);
    if (den) q.set('den', den);

    return q;
}

const trang = taoPhanTrang({
    ds: DS.nhatKy,
    boLoc,
    veDong,
    vao: bang,
    nutThem,
    o,
    khiTrong: 'Không có dòng nhật ký nào khớp bộ lọc.',
    loiChung: 'Không đọc được nhật ký.',
});

if (o) {
    formLoc.addEventListener('submit', (ev) => {
        ev.preventDefault();
        trang.lamMoi();
    });

    document.getElementById('xoa-loc').addEventListener('click', () => {
        formLoc.reset();
        trang.lamMoi();
    });

    trang.lamMoi();
}
