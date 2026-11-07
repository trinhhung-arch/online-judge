import { goi, LoiApi, accessToken, phien } from './api.js';
import { chu, bao } from './khung.js';
import { DUONG } from './duong-dan.js';
import { moLuong } from './sse.js';

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

    taiRest().then((duoc) => {
        if (!duoc) return;
        if (accessToken()) {
            moSse();
        } else {
            batPoll('bạn chưa đăng nhập');
        }
    });

    window.addEventListener('pagehide', () => {
        dongLuong?.();
        if (nhipPoll) clearInterval(nhipPoll);
    });

    return { taiLai: () => taiRest(true) };
}
