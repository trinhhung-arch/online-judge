/**
 * Bảng xếp hạng — FR-CON-04, 05, 06. Bước G6 (REST) và G7 (SSE).
 *
 * ★ REST TRƯỚC, SSE SAU — VÀ THỨ TỰ ẤY LÀ THIẾT KẾ, KHÔNG PHẢI TIẾN ĐỘ
 *
 * `oj-api/CLAUDE.md` mục 4: fallback REST là **bắt buộc**, không phải phương án dự phòng.
 * Kết nối SẼ đứt — Cloudflare Tunnel giới hạn thời gian sống của một luồng.
 *
 * Nên trang này luôn vẽ bằng REST trước, rồi mới nâng cấp lên SSE. Nhờ vậy đường fallback là
 * đường đã chạy thật ở mỗi lần mở trang. Làm ngược lại — SSE trước, REST chỉ khi đứt — thì
 * fallback là đoạn code chưa ai chạy bao giờ, và nó được chạy lần đầu vào đúng lúc kết nối
 * đứt giữa kỳ thi.
 *
 * ★ ĐỨT THÌ QUAY VỀ POLL, KHÔNG PHẢI ĐỨNG IM
 *
 * `sse.js` không tự kết nối lại (có chủ ý — xem javadoc của nó). Ở đây `onDut` bật một nhịp
 * poll bằng REST. Người dùng thấy bảng chậm hơn vài giây; họ KHÔNG thấy một bảng đứng im mà
 * không biết nó đã chết.
 *
 * ★ TÊN SỰ KIỆN SSE LÀ `submission` CHO CẢ HAI LUỒNG
 *
 * `SseEmitters.send` viết cứng tên ấy từ M3. Đó là một vết cũ, không phải một hợp đồng — nên
 * ở đây bỏ qua tên và chỉ đọc payload. Bám vào tên là bám vào một chi tiết sẽ đổi.
 */

import { goi, LoiApi, accessToken, phien } from './api.js';
import { chu, bao } from './khung.js';
import { DUONG } from './duong-dan.js';
import { moLuong } from './sse.js';

/** Nhịp poll khi SSE không dùng được. Khớp `oj.contest.standings-interval` của server. */
const NHIP_POLL_MS = 2000;

export function gan(contestId, { o }) {
    const than = document.getElementById('bang-xep-hang');
    const khuDongBang = document.getElementById('bang-dong-bang');
    const nguon = document.getElementById('nguon-bang');

    let dongLuong = null;
    let nhipPoll = null;
    const toiLaAi = phien()?.userId ?? null;

    function penalty(giay) {
        if (!giay) return '—';
        return `${Math.round(giay / 60)} phút`;
    }

    function veDong(d) {
        const tr = chu('tr');
        // ★ Dòng của chính mình được đánh dấu bằng CHỮ, không chỉ bằng nền: người dùng
        // trình đọc màn hình cũng cần biết đâu là mình.
        const laToi = toiLaAi !== null && d.userId === toiLaAi;

        tr.append(chu('td', d.hang ?? '—'));

        const oTen = chu('td');
        oTen.append(chu('span', d.displayName || d.handle));
        if (laToi) {
            oTen.append(chu('span', ' — bạn', 'goi-y'));
            tr.setAttribute('aria-current', 'true');
        }
        tr.append(oTen);

        tr.append(chu('td', d.soBaiDat));
        tr.append(chu('td', d.tongDiem));
        tr.append(chu('td', penalty(d.penaltyGiay)));

        // FR-CON-05: số bài đang chờ sau đóng băng là nghi thức của một kỳ thi ICPC —
        // nó nói "người này có thể đang vượt bạn mà bạn chưa thấy".
        tr.append(chu('td', d.soBaiChoSauFreeze || '—'));
        return tr;
    }

    function ve(b) {
        khuDongBang.hidden = !b.dongBang;
        if (b.dongBang) {
            khuDongBang.className = 'thong-bao';
            khuDongBang.textContent =
                'Bảng đã ĐÓNG BĂNG. Đây là bản chụp — các bài nộp sau thời điểm đóng băng '
                + 'không được tính vào thứ hạng hiển thị, nhưng cột "Chờ" cho biết có bao '
                + 'nhiêu bài đang đợi.';
        }

        const cac = [...b.top];
        // Người ngoài top 50 vẫn phải thấy mình — FR-CON-04 "top 50 + vùng quanh mình".
        if (b.cuaToi && !cac.some((d) => d.userId === b.cuaToi.userId)) {
            cac.push(b.cuaToi);
        }
        than.replaceChildren(...cac.map(veDong));

        if (!cac.length) {
            const tr = chu('tr');
            tr.append(chu('td', 'Chưa có ai ghi điểm.'));
            than.append(tr);
        }
    }

    async function taiRest(imLang = false) {
        try {
            ve(await goi(DUONG.kyThi.bangXepHang(contestId)));
            if (!imLang) bao(o, '');
            return true;
        } catch (e) {
            if (!imLang) {
                bao(o, e instanceof LoiApi ? e.message : 'Không tải được bảng xếp hạng.', 'loi');
            }
            return false;
        }
    }

    function batPoll(lyDo) {
        if (nhipPoll) return;
        nguon.textContent = `Cập nhật mỗi ${NHIP_POLL_MS / 1000} giây (${lyDo}).`;
        nhipPoll = setInterval(() => taiRest(true), NHIP_POLL_MS);
    }

    function moSse() {
        dongLuong = moLuong(DUONG.kyThi.luongBang(contestId), accessToken(), {
            // Bỏ qua tên sự kiện — xem javadoc đầu file.
            onSuKien: (_ten, duLieu) => {
                if (duLieu) ve(duLieu);
            },
            onDut: () => {
                dongLuong = null;
                batPoll('luồng trực tiếp đã đứt');
            },
        });
        nguon.textContent = 'Cập nhật trực tiếp.';
    }

    // ★ REST trước. Chỉ mở SSE khi lượt đọc đầu đã thành công — mở một luồng tới một kỳ thi
    // không tồn tại chỉ để nhận lỗi lần thứ hai là làm ồn cả log lẫn người dùng.
    taiRest().then((duoc) => {
        if (!duoc) return;
        if (accessToken()) {
            moSse();
        } else {
            // Khách chưa đăng nhập không có token để đặt vào header, và `sse.js` cố ý không
            // bao giờ đưa token vào query string. Họ vẫn xem được bảng, chỉ qua poll.
            batPoll('bạn chưa đăng nhập');
        }
    });

    // Rời trang mà không dọn là để lại một luồng SSE và một listener Redis phía server cho
    // mỗi lần bấm F5 — và đây là trang người ta bấm F5 nhiều nhất trong cả kỳ thi.
    window.addEventListener('pagehide', () => {
        dongLuong?.();
        if (nhipPoll) clearInterval(nhipPoll);
    });

    return { taiLai: () => taiRest(true) };
}
