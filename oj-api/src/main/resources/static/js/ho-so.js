/**
 * Hồ sơ và đổi mật khẩu — FR-AUTH-04, FR-AUTH-05. Bước G2.
 *
 * ★ ĐỔI MẬT KHẨU THÀNH CÔNG NGHĨA LÀ PHIÊN NÀY ĐÃ CHẾT
 *
 * `ChangePasswordUseCase` thu hồi MỌI refresh token, kể cả cái vừa dùng để gọi request này
 * (FR-AUTH-04). Access token còn hiệu lực tối đa 15 phút nữa, nhưng không làm mới được — nên
 * người dùng sẽ bị đá ra vào một lúc ngẫu nhiên trong 15 phút tới, ở một trang bất kỳ, không
 * hiểu vì sao.
 *
 * Trang này xoá phiên NGAY và tự đưa về đăng nhập kèm lý do. Chờ token tự chết là để người
 * dùng gặp một lỗi khó hiểu thay cho một câu giải thích.
 *
 * ★ `preferredLanguageId` LÀ SỐ, KHÔNG PHẢI CHUỖI
 *
 * `UpdateProfileRequest` nhận `Short`. `FormData` trả mọi thứ dạng chuỗi, và `""` cho ô
 * "Chưa chọn". Gửi `""` thì Jackson từ chối; gửi `"1"` thì nó nhận. Nên phải chuyển tay:
 * rỗng -> `null`, còn lại -> số.
 */

import { goi, LoiApi, xoaPhien } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DUONG } from './duong-dan.js';

const o = khoiDong({ doiDangNhap: true });
if (o) {
    const formHoSo = document.getElementById('form-ho-so');
    const formMatKhau = document.getElementById('form-mat-khau');
    const chonNgonNgu = document.getElementById('ngon-ngu');
    const chiSoDoc = document.getElementById('chi-so-doc');

    const VAI_TRO = { USER: 'Người dùng', SETTER: 'Người ra đề', ADMIN: 'Quản trị viên' };

    function dongChiSoDoc(nhan, giaTri) {
        chiSoDoc.append(chu('dt', nhan), chu('dd', giaTri));
    }

    async function taiHoSo() {
        const hs = await goi(DUONG.toi.hoSo);

        chiSoDoc.replaceChildren();
        dongChiSoDoc('Tên đăng nhập', hs.handle);
        dongChiSoDoc('Email', hs.email);
        dongChiSoDoc('Vai trò', VAI_TRO[hs.role] || hs.role);
        dongChiSoDoc('Tham gia', gio(hs.createdAt));

        formHoSo.displayName.value = hs.displayName || '';
        // Đặt sau khi danh sách ngôn ngữ đã nạp; nếu chưa thì lần nạp xong sẽ đặt lại.
        chonNgonNgu.value = hs.preferredLanguageId ?? '';
        return hs;
    }

    async function taiNgonNgu(idDangChon) {
        try {
            for (const n of await goi(DUONG.ngonNgu)) {
                const opt = chu('option', n.displayName);
                opt.value = n.id;
                chonNgonNgu.append(opt);
            }
            chonNgonNgu.value = idDangChon ?? '';
        } catch {
            // Tiện nghi, không phải nội dung chính: hồ sơ vẫn sửa được tên hiển thị.
            chonNgonNgu.disabled = true;
            chonNgonNgu.insertAdjacentElement('afterend',
                chu('p', 'Không tải được danh sách ngôn ngữ.', 'goi-y'));
        }
    }

    formHoSo.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const nut = formHoSo.querySelector('button[type=submit]');
        nut.disabled = true;
        bao(o, '');
        try {
            const chon = chonNgonNgu.value;
            await goi(DUONG.toi.hoSo, {
                method: 'PATCH',
                body: {
                    displayName: formHoSo.displayName.value.trim(),
                    preferredLanguageId: chon === '' ? null : Number(chon),
                },
            });
            bao(o, 'Đã lưu hồ sơ.', 'on');
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : 'Không lưu được hồ sơ.', 'loi');
        } finally {
            nut.disabled = false;
        }
    });

    formMatKhau.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const nut = formMatKhau.querySelector('button[type=submit]');

        // Kiểm ở client CHỈ để đỡ một vòng mạng cho một lỗi hiển nhiên. Server vẫn là nơi
        // quyết định — nó không tin gì ở đây.
        if (formMatKhau.matKhauMoi.value !== formMatKhau.matKhauLai.value) {
            bao(o, 'Hai ô mật khẩu mới không giống nhau.', 'loi');
            formMatKhau.matKhauLai.focus();
            return;
        }

        nut.disabled = true;
        bao(o, '');
        try {
            await goi(DUONG.toi.matKhau, {
                method: 'POST',
                body: {
                    matKhauCu: formMatKhau.matKhauCu.value,
                    matKhauMoi: formMatKhau.matKhauMoi.value,
                },
            });
            // Xoá TRƯỚC khi chuyển trang: refresh token vừa bị thu hồi ở server, giữ lại
            // chỉ để `api.js` thử làm mới một lần rồi thất bại.
            xoaPhien();
            location.href = '/login.html?vi-sao=doi-mat-khau';
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : 'Không đổi được mật khẩu.', 'loi');
            nut.disabled = false;
        }
    });

    taiHoSo()
        .then((hs) => taiNgonNgu(hs.preferredLanguageId))
        .catch((e) => bao(o, e instanceof LoiApi ? e.message : 'Không tải được hồ sơ.', 'loi'));
}
