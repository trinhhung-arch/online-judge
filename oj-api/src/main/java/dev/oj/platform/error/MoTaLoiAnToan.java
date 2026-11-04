package dev.oj.platform.error;

import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;

/**
 * ★ Mô tả một lỗi đọc thân request mà KHÔNG chép câu lỗi của nó — bất biến #9.
 *
 * <h2>Vì sao không in câu lỗi, dù chỉ vào log</h2>
 * Câu lỗi của bộ phân tích JSON chép chính dữ liệu người gửi. Jackson 3 viết
 * {@code Unrecognized token 'hunter2'} cho thân {@code {"password":hunter2}} (thiếu ngoặc
 * kép), và {@code from String "…"} cho một giá trị enum sai. Spring lại chép câu ấy vào câu
 * của chính nó ({@code JSON parse error: …}), nên không tầng nào trong chuỗi nguyên nhân an
 * toàn để in. Bản cũ của {@code GlobalExceptionHandler.handleUnreadable} in câu lỗi của
 * nguyên nhân sâu nhất — tức là in mật khẩu ra log WARN (tìm ra 2026-11-05,
 * {@code JsonHongKhongLoVaoLogTest}).
 *
 * <h2>Thứ được in</h2>
 * Chỉ thứ không bao giờ đến từ nội dung người gửi: tên lớp ngoại lệ, vị trí, và đường tới
 * trường hỏng. Tên trường là tên trong DTO của ta; riêng thân kiểu {@code Map} thì khoá là chữ
 * của người gửi, nên đường bị cắt ở {@link #TRAN_DUONG_TRUONG} ký tự. Ba thứ ấy đủ để gỡ lỗi:
 * "{@code InvalidFormatException tại byte 10 ở trường .vaiTro}" nói đúng chỗ hỏng mà không nói
 * người ta đã gõ gì.
 */
final class MoTaLoiAnToan {

    static final int TRAN_DUONG_TRUONG = 80;

    private MoTaLoiAnToan() {
    }

    /**
     * Tên lớp lấy từ nguyên nhân sâu nhất; vị trí và tên trường lấy từ lỗi Jackson sâu nhất —
     * hai thứ không phải lúc nào cũng trùng nhau: constructor của một record ném
     * {@code IllegalArgumentException} thì Jackson bọc nó lại, và nguyên nhân sâu nhất không còn
     * là lỗi Jackson nữa.
     */
    static String cua(Throwable e) {
        Throwable goc = e;
        JacksonException loiJackson = e instanceof JacksonException j ? j : null;
        while (goc.getCause() != null && goc.getCause() != goc) {
            goc = goc.getCause();
            if (goc instanceof JacksonException j) {
                loiJackson = j;
            }
        }
        StringBuilder moTa = new StringBuilder(goc.getClass().getSimpleName());
        if (loiJackson != null) {
            themViTri(moTa, loiJackson.getLocation());
            String truong = duongTruong(loiJackson);
            if (!truong.isEmpty()) {
                moTa.append(" ở trường ").append(truong);
            }
        }
        return moTa.toString();
    }

    private static void themViTri(StringBuilder moTa, TokenStreamLocation viTri) {
        if (viTri == null) {
            return;
        }
        if (viTri.getLineNr() > 0) {
            moTa.append(" tại dòng ").append(viTri.getLineNr())
                    .append(" cột ").append(viTri.getColumnNr());
        } else if (viTri.getByteOffset() >= 0) {
            moTa.append(" tại byte ").append(viTri.getByteOffset());
        } else if (viTri.getCharOffset() >= 0) {
            moTa.append(" tại ký tự ").append(viTri.getCharOffset());
        }
    }

    private static String duongTruong(JacksonException loi) {
        StringBuilder duong = new StringBuilder();
        for (JacksonException.Reference buoc : loi.getPath()) {
            if (buoc.getPropertyName() != null) {
                duong.append('.').append(buoc.getPropertyName());
            } else if (buoc.getIndex() >= 0) {
                duong.append('[').append(buoc.getIndex()).append(']');
            }
        }
        return duong.length() <= TRAN_DUONG_TRUONG ? duong.toString()
                : duong.substring(0, TRAN_DUONG_TRUONG) + "…";
    }
}
