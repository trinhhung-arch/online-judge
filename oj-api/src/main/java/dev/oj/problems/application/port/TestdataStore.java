package dev.oj.problems.application.port;

import java.io.InputStream;

/**
 * Kho testdata <b>content-addressed</b> — FR-PROB-03, Bước 4.11.
 *
 * <h2>★ Khoá là sha256 của nội dung, không phải một cái tên</h2>
 * Ba hệ quả, và cả ba đều là lý do chọn cách này:
 *
 * <ol>
 *   <li><b>Không có "ghi đè".</b> Hai lần nạp cùng một nội dung cho cùng một khoá. Nghĩa là
 *       {@code testdata_versions} v1 và v2 dùng chung mọi test không đổi, và
 *       {@code FR-PROB-10} ("sửa testdata thì tạo version mới") không tốn thêm dung lượng cho
 *       phần không đổi.</li>
 *   <li><b>Worker kiểm được.</b> Nó băm lại thứ tải về và đối chiếu với khoá — nên một kho bị
 *       xâm nhập không đổi được nội dung test mà worker không phát hiện. Xem
 *       {@code TestdataFetcher} ở {@code oj-worker}.</li>
 *   <li><b>Sửa đề không làm hỏng bài nộp cũ.</b> Một bài nộp ghi lại
 *       {@code testdata_version} đã dùng, và các test của version ấy vẫn nằm nguyên ở khoá cũ.
 *       Đó là điều kiện để câu hỏi <i>"vì sao verdict hôm nay khác hôm qua"</i> có câu trả lời.</li>
 * </ol>
 *
 * <h2>Bất biến #1 — nội dung test ẩn KHÔNG bao giờ rời khỏi đây về phía người dùng</h2>
 * Port này chỉ được gọi từ {@code problems.infrastructure} và từ job nạp dữ liệu. Không có
 * endpoint công khai nào đọc nó, và {@link #doc} tồn tại cho đường worker (M5) chứ không phải
 * cho một API. Nếu một ngày có ai định thêm {@code GET /problems/{id}/testdata}, đọc lại
 * bất biến #1 rồi dừng lại và hỏi.
 */
public interface TestdataStore {

    /**
     * Ghi một nội dung và trả về khoá của nó.
     *
     * <p>Idempotent theo định nghĩa: nội dung giống nhau cho khoá giống nhau.
     *
     * @param sha256 khoá đã tính sẵn — <b>người gọi phải băm trước</b>, vì stream chỉ đọc được
     *               một lần và ta không muốn giữ 200MB trong bộ nhớ để băm rồi ghi
     * @param soByte kích thước thật, để kho không phải đệm toàn bộ vào RAM
     */
    void luu(String sha256, InputStream noiDung, long soByte);

    boolean daCo(String sha256);

    /** Đọc lại. Người gọi <b>phải</b> đóng stream. */
    InputStream doc(String sha256);
}
