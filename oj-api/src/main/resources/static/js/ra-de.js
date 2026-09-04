/**
 * Soạn đề — FR-PROB-01, 07, 08. Bước G9.
 *
 * ★ MÁY CHỦ KHÔNG CÓ ENDPOINT "NHỮNG BẢN NHÁP CỦA TÔI"
 *
 * `GET /api/v1/problems` lọc `status = 'PUBLISHED'`, và `GET /problems/{code}` cũng chỉ thấy
 * `PUBLISHED`/`RETIRED`. Nghĩa là một bản nháp KHÔNG tra được bằng mã chữ; thứ duy nhất mở
 * lại được nó là `problemId` mà máy chủ trả về đúng một lần, lúc tạo.
 *
 * Trang này giữ danh sách id ấy trong `localStorage` để tác giả không phải chép tay. Đó là
 * bộ nhớ của MỘT TRÌNH DUYỆT, không phải dữ liệu — nói rõ ở HTML, vì một danh sách trông
 * giống "đề của tôi" mà thật ra là cache sẽ làm người ta tưởng đã mất đề khi đổi máy.
 *
 * ★ FORM NÀY GHI ĐÈ TRỌN BẢN GHI, NÊN NÓ PHẢI ĐỌC ĐƯỢC TRỌN BẢN GHI
 *
 * `PUT` đặt cả mười trường, kể cả những trường người dùng không chạm tới. Nghĩa là mọi thứ
 * form gửi đi mà không đọc vào trước sẽ bị xoá.
 *
 * Đã từng đúng như thế: `GET .../edit` trả `ProblemResponse` — bản dùng cho trang đề công
 * khai — vốn cố ý không mang `checkerEpsilon` lẫn `allowPublicSolutions`. Một vòng "mở đề
 * float → sửa tiêu đề → Lưu" xoá sạch sai số, và đề vẫn chấm, chỉ chấm sai. Nay endpoint trả
 * `ProblemAuthoringResponse` mang đủ, và `ProblemAuthoringRoundTripTest` giữ cho hai danh
 * sách trường ấy không lệch nhau nữa.
 *
 * Bài học vẫn còn giá trị ở đây: thêm một ô vào form này thì phải thêm cả ở `doVaoForm` lẫn
 * `docTuForm`. Thêm mỗi chỗ thứ hai là viết một cái nút xoá dữ liệu.
 */

import { goi, LoiApi, phien } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong } from './trang.js';
import { DUONG } from './duong-dan.js';
import { ganTestdata } from './testdata.js';

const KHOA_GAN_DAY = 'oj.ra-de.gan-day';

const o = khoiDong({ doiDangNhap: true });

const form = document.getElementById('form-de');
const tieuDeForm = document.getElementById('tieu-de-form');
const nutLuu = document.getElementById('luu');
const nutXuatBan = document.getElementById('xuat-ban');
const nutGoXuong = document.getElementById('go-xuong');
const khuTestdata = document.getElementById('khu-testdata');
const khuTrangThai = document.getElementById('trang-thai-de');
const canhBaoPhanHoi = document.getElementById('canh-bao-phan-hoi');
const ganDay = document.getElementById('gan-day');

/** `null` = đang soạn đề mới; một số = đang sửa đề đã có. */
let deHienTai = null;

const GIAI_THICH_PHAN_HOI = {
    NONE: 'Kín nhất. Thí sinh chỉ biết đúng hay sai — không rút trích được gì từ verdict.',
    TEST_INDEX: 'Mặc định. Nói sai ở test thứ mấy; đủ để gỡ lỗi, chưa đủ để dựng lại bộ test.',
    SAMPLE_DETAIL: 'Hở nhất. Chỉ hiện chi tiết của test VÍ DỤ — vốn đã công khai — nhưng hãy '
        + 'chắc rằng những test bạn đánh dấu "sample" thật sự là test bạn muốn cho xem.',
};

// ---------------------------------------------------------------------------
// Danh sách gần đây — bộ nhớ của trình duyệt này
// ---------------------------------------------------------------------------

function docGanDay() {
    try {
        const ds = JSON.parse(localStorage.getItem(KHOA_GAN_DAY) || '[]');
        return Array.isArray(ds) ? ds : [];
    } catch {
        return [];
    }
}

function nhoLai(problemId, code) {
    const ds = docGanDay().filter((x) => x.id !== problemId);
    ds.unshift({ id: problemId, code });
    try {
        localStorage.setItem(KHOA_GAN_DAY, JSON.stringify(ds.slice(0, 12)));
    } catch {
        // Chế độ riêng tư hoặc hết dung lượng. Mất lối tắt, không mất đề — im lặng là đúng.
    }
    veGanDay();
}

function veGanDay() {
    const ds = docGanDay();
    ganDay.replaceChildren();
    if (!ds.length) return;

    ganDay.append(chu('p', 'Mở nhanh (nhớ trên máy này):', 'goi-y'));
    const hang = chu('p', null, 'hang');
    for (const x of ds) {
        const nut = chu('button', `#${x.id} · ${x.code || 'chưa đặt mã'}`, 'phu');
        nut.type = 'button';
        nut.addEventListener('click', () => moDe(x.id));
        hang.append(nut);
    }
    ganDay.append(hang);
}

// ---------------------------------------------------------------------------
// Đổ dữ liệu vào form và đọc ngược ra
// ---------------------------------------------------------------------------

const NHAN_TRANG_THAI = {
    DRAFT: 'Bản nháp — chưa ai ngoài bạn thấy đề này.',
    PUBLISHED: 'Đã xuất bản — mọi người thấy đề và nộp bài được.',
    RETIRED: 'Đã gỡ xuống — không nhận bài nộp mới. Bài đã nộp giữ nguyên.',
};

function datCheDo(id, code, trangThai) {
    deHienTai = id;
    tieuDeForm.textContent = id ? `Sửa đề #${id}${code ? ` · ${code}` : ''}` : 'Đề mới';
    nutLuu.textContent = id ? 'Lưu thay đổi' : 'Tạo đề';
    khuTestdata.hidden = !id;

    // ★ Chỉ hiện nút DÙNG ĐƯỢC. Bày cả "Xuất bản" lẫn "Gỡ xuống" cho một đề đang PUBLISHED
    // là bắt người ta đoán đề đang ở đâu — mà chính máy chủ vừa nói ra điều đó.
    nutXuatBan.hidden = !id || trangThai === 'PUBLISHED';
    nutGoXuong.hidden = !id || trangThai !== 'PUBLISHED';

    khuTrangThai.hidden = !id;
    if (id) {
        khuTrangThai.className = 'thong-bao';
        khuTrangThai.textContent = NHAN_TRANG_THAI[trangThai] || trangThai || '';
    }
}

function doVaoForm(de) {
    form.code.value = de.code || '';
    form.elements.title.value = de.title || '';
    form.statementMd.value = de.statementMd || '';
    form.timeLimitMs.value = de.timeLimitMs;
    form.memoryLimitKb.value = de.memoryLimitKb;
    form.checkerType.value = de.checkerType || 'token';
    form.checkerEpsilon.value = de.checkerEpsilon ?? '';
    form.allowPublicSolutions.checked = Boolean(de.allowPublicSolutions);
    form.scoringMode.value = de.scoringMode || 'ALL_OR_NOTHING';
    form.feedbackLevel.value = de.feedbackLevel || 'TEST_INDEX';
    // Hai trường máy chủ không trả về: để nguyên giá trị mặc định của form, và câu cảnh báo
    // ở `datCheDo` nói cho tác giả biết vì sao chúng trống.
    veGiaiThichPhanHoi();
}

function docTuForm() {
    const eps = form.checkerEpsilon.value.trim();
    return {
        code: form.code.value.trim(),
        title: form.elements.title.value.trim(),   // `form.title` = thuộc tính HTML
        statementMd: form.statementMd.value,
        timeLimitMs: Number(form.timeLimitMs.value),
        memoryLimitKb: Number(form.memoryLimitKb.value),
        checkerType: form.checkerType.value,
        checkerEpsilon: eps === '' ? null : eps,
        scoringMode: form.scoringMode.value,
        feedbackLevel: form.feedbackLevel.value,
        allowPublicSolutions: form.allowPublicSolutions.checked,
    };
}

function veGiaiThichPhanHoi() {
    canhBaoPhanHoi.textContent = GIAI_THICH_PHAN_HOI[form.feedbackLevel.value] || '';
}

// ---------------------------------------------------------------------------
// Hành động
// ---------------------------------------------------------------------------

async function moDe(id) {
    bao(o, '');
    try {
        const de = await goi(DUONG.de.soan(id));
        doVaoForm(de);
        datCheDo(id, de.code, de.status);
        nhoLai(id, de.code);
        ganTestdata(id, { o });
        bao(o, `Đã mở đề #${id}.`, 'on');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không mở được đề.', 'loi');
    }
}

async function luu(ev) {
    ev.preventDefault();
    nutLuu.disabled = true;
    bao(o, '');
    try {
        const than = docTuForm();
        if (deHienTai) {
            await goi(DUONG.de.sua(deHienTai), { method: 'PUT', body: than });
            nhoLai(deHienTai, than.code);
            bao(o, 'Đã lưu.', 'on');
        } else {
            const kq = await goi(DUONG.de.tao, { method: 'POST', body: than });
            datCheDo(kq.problemId, than.code, 'DRAFT');
            nhoLai(kq.problemId, than.code);
            ganTestdata(kq.problemId, { o });
            bao(o, `Đã tạo đề #${kq.problemId}. Ghi lại số này — máy chủ không có danh sách `
                + 'bản nháp để tra lại.', 'on');
        }
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không lưu được đề.', 'loi');
    } finally {
        nutLuu.disabled = false;
    }
}

async function doiTrangThai(duongDan, hoi, xong) {
    if (!confirm(hoi)) return;
    bao(o, '');
    try {
        await goi(duongDan, { method: 'POST' });
        bao(o, xong, 'on');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không đổi được trạng thái đề.', 'loi');
    }
}

// ---------------------------------------------------------------------------

if (o) {
    const vaiTro = phien()?.role;
    if (vaiTro !== 'SETTER' && vaiTro !== 'ADMIN') {
        // Không phải chốt bảo mật — `AuthorProblemUseCase` mới là nơi chặn (bất biến #11).
        // Nói trước để người ta không gõ xong cả đề rồi mới nhận 403.
        bao(o, 'Tài khoản của bạn không có quyền soạn đề. Máy chủ sẽ từ chối mọi thao tác ở '
            + 'trang này.', 'loi');
    }

    veGanDay();
    veGiaiThichPhanHoi();
    datCheDo(null, null, null);

    form.addEventListener('submit', luu);
    form.feedbackLevel.addEventListener('change', veGiaiThichPhanHoi);

    document.getElementById('form-mo').addEventListener('submit', (ev) => {
        ev.preventDefault();
        moDe(Number(ev.target.problemId.value));
    });

    document.getElementById('de-moi').addEventListener('click', () => {
        form.reset();
        datCheDo(null, null, null);
        veGiaiThichPhanHoi();
        bao(o, 'Đang soạn một đề mới.', 'on');
    });

    nutXuatBan.addEventListener('click', () => doiTrangThai(
        DUONG.de.xuatBan(deHienTai),
        'Xuất bản đề này? Sau đó mọi người thấy đề và nộp bài được.',
        'Đã xuất bản.'));

    nutGoXuong.addEventListener('click', () => doiTrangThai(
        DUONG.de.goXuong(deHienTai),
        'Gỡ đề xuống? Đề sẽ không nhận bài nộp mới nữa. Bài đã nộp giữ nguyên.',
        'Đã gỡ đề xuống.'));
}
