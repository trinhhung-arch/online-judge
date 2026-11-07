export const DUONG = {
    auth: {
        captcha:  '/api/v1/auth/captcha',
        dangKy:   '/api/v1/auth/register',
        dangNhap: '/api/v1/auth/login',
        lamMoi:   '/api/v1/auth/refresh',
        dangXuat: '/api/v1/auth/logout',
    },

    toi: {
        hoSo:    '/api/v1/me',
        matKhau: '/api/v1/me/password',
        haiLop:  '/api/v1/me/2fa',
        haiLopBatDau:  '/api/v1/me/2fa/bat-dau',
        haiLopXacNhan: '/api/v1/me/2fa/xac-nhan',

        xacMinhEmail:        '/api/v1/me/xac-minh-email',
        xacMinhEmailXacNhan: '/api/v1/me/xac-minh-email/xac-nhan',
    },

    de: {
        theoMa: (ma) => `/api/v1/problems/${encodeURIComponent(ma)}`,

        tao:      '/api/v1/problems',
        sua:      (id) => `/api/v1/problems/${encodeURIComponent(id)}`,
        soan:     (id) => `/api/v1/problems/${encodeURIComponent(id)}/edit`,
        xuatBan:  (id) => `/api/v1/problems/${encodeURIComponent(id)}/publish`,
        goXuong:  (id) => `/api/v1/problems/${encodeURIComponent(id)}/retire`,
        xoa:      (id) => `/api/v1/problems/${encodeURIComponent(id)}`,
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
        soanDe:      (id) => `/api/v1/contests/${encodeURIComponent(id)}/problems/new`,
        goDe:        (id, pid) => `/api/v1/contests/${encodeURIComponent(id)}`
            + `/problems/${encodeURIComponent(pid)}`,
        congBo:      (id) => `/api/v1/contests/${encodeURIComponent(id)}/reveal`,
    },

    ngonNgu:   '/api/v1/languages',
    trangThai: '/api/v1/status',
};

export const DS = {
    de:     { url: '/api/v1/problems',    khoaSize: 'size'  },
    baiNop: { url: '/api/v1/submissions', khoaSize: 'limit' },
    kyThi:  { url: '/api/v1/contests',    khoaSize: 'size'  },
    nhatKy:    { url: '/api/v1/admin/audit-log', khoaSize: 'size' },
    nguoiDung: { url: '/api/v1/admin/users',     khoaSize: 'size' },
};

export const TRANG_THAI_KY_THI = {
    SAP_DIEN_RA:  ['Sắp diễn ra', 'cho'],
    DANG_CHAY:    ['Đang diễn ra', 'AC'],
    DA_KET_THUC:  ['Đã kết thúc', ''],
};

export const VERDICT = [
    ['AC',  'AC · Đúng'],
    ['WA',  'WA · Sai kết quả'],
    ['TLE', 'TLE · Quá thời gian'],
    ['MLE', 'MLE · Quá bộ nhớ'],
    ['RE',  'RE · Lỗi khi chạy'],
    ['CE',  'CE · Không biên dịch được'],
    ['IE',  'IE · Lỗi hệ thống'],
];
