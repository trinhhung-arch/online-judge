package dev.oj.contract;

/**
 * Ba đường dẫn nội bộ và tên header xác thực — <b>một nguồn sự thật cho cả hai tiến trình</b>.
 *
 * <h2>Vì sao file này phải tồn tại</h2>
 * Trước nó, {@code oj-api} và {@code oj-worker} mỗi bên tự gõ một chuỗi giống nhau:
 * {@code InternalSecretFilter.HEADER} và {@code JudgeApiClient.SECRET_HEADER}. Không có gì
 * bắt được lúc chúng lệch nhau — trình biên dịch im lặng, test hai bên vẫn xanh vì mỗi bên
 * dùng hằng của chính mình, và triệu chứng duy nhất là <b>mọi request từ worker nhận 401</b>
 * với một dòng log nói "thiếu hoặc sai header" mà không nói header nào.
 *
 * <p>Đó đúng là loại lỗi mà {@code oj-contract} sinh ra để ngăn: thứ gì hai bên phải đồng ý
 * thì nằm ở đây, không nằm ở hai chỗ.
 *
 * <h2>Hai điều về bề mặt này</h2>
 * <ul>
 *   <li><b>Không nằm dưới {@code /api/v1/}.</b> Cloudflare Tunnel chỉ publish {@code /api/v1/**},
 *       nên ba đường dẫn dưới đây không có lối vào từ internet. Đừng đặt
 *       {@code server.servlet.context-path}: nó bọc toàn bộ ứng dụng và sẽ kéo cả ba vào.</li>
 *   <li><b>Xác thực bằng shared secret</b> đọc từ env, không phải JWT người dùng. Worker không
 *       phải một người dùng — cho nó một tài khoản là tạo một tài khoản ghi được verdict cho
 *       mọi bài nộp.</li>
 * </ul>
 *
 * <p>Các hằng đều là {@code String} biên dịch được, nên dùng thẳng trong {@code @RequestMapping}.
 */
public final class JudgeEndpoints {

    /** Tiền tố chung. Cấu hình security lọc theo đúng chuỗi này. */
    public static final String BASE = "/internal/judge";

    /** Worker xin việc: {@code ClaimRequestDto} → 200 {@link JudgeJobDto} | 204 (hết việc). */
    public static final String CLAIM = BASE + "/claim";

    /** Worker trả kết quả cuối: {@link JudgeResultDto} → 204. */
    public static final String RESULT = BASE + "/result";

    /** Tiến độ giữa chừng theo lô 20 test: {@link JudgeProgressDto} → 204. <b>Dùng từ M3.</b> */
    public static final String PROGRESS = BASE + "/progress";

    /**
     * Header mang shared secret.
     *
     * <p>Tên bắt đầu bằng {@code X-} theo thói quen cũ; giữ nguyên vì đổi nó là đổi hợp đồng.
     * <b>Không bao giờ log giá trị của header này</b> — kể cả giá trị sai, vì một lần gõ nhầm
     * của chính worker sẽ đưa secret thật vào file log (bất biến #9).
     */
    public static final String SECRET_HEADER = "X-Internal-Secret";

    private JudgeEndpoints() {
    }
}
