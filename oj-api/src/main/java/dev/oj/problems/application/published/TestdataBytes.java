package dev.oj.problems.application.published;

import java.io.InputStream;
import java.util.Optional;

/**
 * ★ Bề mặt <b>công khai</b> của {@code problems} cho {@code judging}: đọc một đối tượng
 * testdata theo hash. Không có gì khác.
 *
 * <h2>Vì sao không tiêm thẳng {@code TestdataStore}</h2>
 * Cùng lập luận đã tạo ra {@code judging.application.published.JudgingQueries} ở M5. Luật
 * ArchUnit 2 cấm module X chạm {@code infrastructure} của module Y, nhưng nó <b>không</b> cấm
 * chạm {@code application.port}. Nếu {@code judging} tiêm {@code TestdataStore} thì
 * {@code luu(sha, stream, soByte)} trở thành API công khai của {@code problems} — và một ngày
 * nào đó sẽ có một đường ghi vào kho testdata đi từ phía chấm bài.
 *
 * <p>Ở đây thì không có gì để ghi. Đó là toàn bộ điểm của file này.
 *
 * <h2>{@code Optional}, không phải ném</h2>
 * {@code TestdataStore.doc} ném khi kho hỏng <i>và</i> khi đối tượng không có — hai chuyện
 * khác nhau, và endpoint phải phân biệt: <b>404</b> cho "hash này không có" và <b>503</b> cho
 * "kho đang chết". Gộp chúng lại thì worker retry mãi một hash không bao giờ tồn tại, hoặc
 * bỏ cuộc với một kho chỉ đang khởi động lại.
 */
public interface TestdataBytes {

    /**
     * @param sha256 hash nội dung, 64 ký tự hex thường
     * @return rỗng nếu không có đối tượng nào mang hash này. Người gọi <b>phải đóng</b> stream
     * @throws dev.oj.problems.domain.ProblemsException {@code UNAVAILABLE} khi kho không dùng được
     */
    Optional<InputStream> doc(String sha256);
}
