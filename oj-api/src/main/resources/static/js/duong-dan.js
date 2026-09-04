/**
 * Chỗ DUY NHẤT biết đường dẫn và hình dạng payload của API — Bước G1.
 *
 * ★ VÌ SAO GOM VÀO MỘT FILE
 *
 * Ba lần liên tiếp khi kiểm thử tay backend, người viết file này gọi sai:
 *
 *   · đăng nhập nhận `dinhDanh`, không phải `handle` hay `email`
 *   · nộp bài nhận `problemId` (số), không phải `problemCode`
 *   · `/submissions` nhận `limit`, còn `problems` nhận `size`
 *
 * Hai cái đầu trả lỗi ngay nên tìm ra nhanh. Cái thứ ba thì KHÔNG: Spring bỏ qua tham số
 * lạ trong im lặng, nên client nhận đúng 20 dòng mặc định và tưởng phân trang đang chạy.
 * Loại lệch đó không bao giờ tự lộ ra.
 *
 * Một hằng viết sai ở đây thì sửa một lần. Viết rải ở tám trang thì sửa bảy lần và quên một.
 *
 * ★ VÀ NÓ ĐƯỢC ÉP BẰNG CI
 *
 * `BeMatFrontendTest` quét mọi chuỗi `/api/v1/...` trong thư mục này và khẳng định từng
 * đường dẫn khớp một `@RequestMapping` có thật. Nó bắt đúng thứ trình biên dịch không thấy:
 * backend đổi tên một đường dẫn, frontend vẫn chạy, và triệu chứng duy nhất là một nút
 * không làm gì cả.
 */

export const DUONG = {
    auth: {
        dangKy:   '/api/v1/auth/register',
        dangNhap: '/api/v1/auth/login',
        lamMoi:   '/api/v1/auth/refresh',
        dangXuat: '/api/v1/auth/logout',
    },

    toi: {
        hoSo:    '/api/v1/me',
        matKhau: '/api/v1/me/password',
    },

    de: {
        theoMa: (ma) => `/api/v1/problems/${encodeURIComponent(ma)}`,

        // ★ Soạn đề dùng problemId (SỐ), còn trang đề dùng code (CHỮ). Hai định danh cho
        // cùng một thứ, và server không nhận lẫn: `/problems/A-PLUS-B` là bài đã xuất bản,
        // `/problems/12/edit` là bản nháp. Trộn hai cái là 404 mà không ai hiểu vì sao.
        tao:      '/api/v1/problems',
        sua:      (id) => `/api/v1/problems/${encodeURIComponent(id)}`,
        soan:     (id) => `/api/v1/problems/${encodeURIComponent(id)}/edit`,
        xuatBan:  (id) => `/api/v1/problems/${encodeURIComponent(id)}/publish`,
        goXuong:  (id) => `/api/v1/problems/${encodeURIComponent(id)}/retire`,
        testdata: (id) => `/api/v1/problems/${encodeURIComponent(id)}/testdata`,
    },

    viec: {
        theoId: (id) => `/api/v1/jobs/${encodeURIComponent(id)}`,
        huy:    (id) => `/api/v1/jobs/${encodeURIComponent(id)}/cancel`,
        cuaToi: '/api/v1/jobs',
    },

    quanTri: {
        bangDieuKhien: '/api/v1/admin/ops',
        chamLai:  (id) => `/api/v1/admin/problems/${encodeURIComponent(id)}/rejudge`,
        anBai:    (id) => `/api/v1/admin/submissions/${encodeURIComponent(id)}/hide`,
        hienBai:  (id) => `/api/v1/admin/submissions/${encodeURIComponent(id)}/unhide`,
        vaiTro:   (id) => `/api/v1/admin/users/${encodeURIComponent(id)}/role`,
        hoatDong: (id) => `/api/v1/admin/users/${encodeURIComponent(id)}/active`,
        anDanh:   (id) => `/api/v1/admin/users/${encodeURIComponent(id)}/anonymize`,

        congTac:    '/api/v1/admin/settings',
        datCongTac: (khoa) => `/api/v1/admin/settings/${encodeURIComponent(khoa)}`,
    },

    baiNop: {
        nop:    '/api/v1/submissions',
        theoId: (id) => `/api/v1/submissions/${encodeURIComponent(id)}`,
        luong:  (id) => `/api/v1/submissions/${encodeURIComponent(id)}/stream`,
    },

    kyThi: {
        theoSlug:    (slug) => `/api/v1/contests/${encodeURIComponent(slug)}`,
        bangXepHang: (id) => `/api/v1/contests/${encodeURIComponent(id)}/standings`,
        luongBang:   (id) => `/api/v1/contests/${encodeURIComponent(id)}/standings/stream`,
        dangKy:      (id) => `/api/v1/contests/${encodeURIComponent(id)}/register`,
        tao:         '/api/v1/contests',
        themDe:      (id) => `/api/v1/contests/${encodeURIComponent(id)}/problems`,
        congBo:      (id) => `/api/v1/contests/${encodeURIComponent(id)}/reveal`,
    },

    ngonNgu:   '/api/v1/languages',
    trangThai: '/api/v1/status',
};

/**
 * ★ Endpoint DANH SÁCH — mỗi cái mang theo tên tham số kích thước trang của CHÍNH NÓ.
 *
 * Server chưa thống nhất: `/submissions` nhận `limit`, những cái còn lại nhận `size`. Chừng
 * nào còn thế thì con số ấy phải đi kèm đường dẫn, chứ không phải nằm trong trí nhớ của
 * người viết trang. `js/phan-trang.js` nhận trọn một mô tả ở đây và không đoán gì.
 */
export const DS = {
    de:     { url: '/api/v1/problems',    khoaSize: 'size'  },
    baiNop: { url: '/api/v1/submissions', khoaSize: 'limit' },
    kyThi:  { url: '/api/v1/contests',    khoaSize: 'size'  },
    nhatKy: { url: '/api/v1/admin/audit-log', khoaSize: 'size' },
};

/** Trạng thái kỳ thi — server suy, client chỉ hiển thị. Xem ListContestsUseCase.TrangThai. */
export const TRANG_THAI_KY_THI = {
    SAP_DIEN_RA:  ['Sắp diễn ra', 'cho'],
    DANG_CHAY:    ['Đang diễn ra', 'AC'],
    DA_KET_THUC:  ['Đã kết thúc', ''],
};

/**
 * Bảy verdict của FR-SUB-04, kèm câu ngắn cho bộ lọc.
 *
 * Đây KHÔNG phải bản dịch của mã lỗi server — `api.js` đã nói rõ frontend không tự dịch.
 * Đây là nhãn cho một `<select>` mà server không có endpoint nào liệt kê.
 */
export const VERDICT = [
    ['AC',  'AC · Đúng'],
    ['WA',  'WA · Sai kết quả'],
    ['TLE', 'TLE · Quá thời gian'],
    ['MLE', 'MLE · Quá bộ nhớ'],
    ['RE',  'RE · Lỗi khi chạy'],
    ['CE',  'CE · Không biên dịch được'],
    ['IE',  'IE · Lỗi hệ thống'],
];
