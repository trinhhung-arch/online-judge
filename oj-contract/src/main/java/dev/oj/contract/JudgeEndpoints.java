package dev.oj.contract;

/**
 * Năm đường dẫn nội bộ và tên header xác thực — <b>một nguồn sự thật cho cả hai tiến trình</b>.
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
     * Worker báo một phép đo tốc độ máy chấm ({@link HostBenchmarkDto}). Ngoài đường
     * {@code nộp bài → verdict}: gọi 15 phút một lần từ luồng lịch, không tiêu một phần nào
     * của ngân sách 2 giây.
     */
    public static final String BENCHMARK = BASE + "/benchmark";

    /**
     * ★ Worker tải nội dung một testcase theo hash: {@code GET .../testdata/{sha256}} →
     * 200 {@code application/octet-stream} | 404.
     *
     * <h2>Vì sao đường này phải tồn tại, và vì sao nó đến muộn</h2>
     * {@link TestcaseMetaDto} cố ý <b>không mang nội dung</b> — chỉ có {@code inputSha256} và
     * {@code outputSha256}. Javadoc của nó viết "worker dùng nó để tải nội dung từ MinIO",
     * nhưng {@code oj-worker/CLAUDE.md} mục 3 cấm worker có MinIO client. Hai câu ấy mâu
     * thuẫn, và hệ quả là <b>không ai viết đoạn vận chuyển</b>: hiện thực {@code TestdataSource}
     * duy nhất đọc một thư mục cục bộ mà không gì đổ dữ liệu vào. Mọi bài nộp trả {@code IE}
     * ngay khi testdata được nạp qua API — phát hiện khi chạy tay ở cuối M6.
     *
     * <p>Đường này giải mâu thuẫn theo hướng giữ nguyên bất biến #3: worker vẫn chỉ biết
     * những đường dẫn liệt kê trong file này, không biết MinIO tồn tại, và không có credential
     * của bất kỳ hạ tầng nào. API là thứ duy nhất chạm kho.
     *
     * <h2>Nó KHÔNG nằm trên đường {@code nộp bài → verdict}</h2>
     * Giống {@code benchmark}. Worker cache theo hash ({@code ContentAddressedCache}), và hash
     * chỉ đổi khi đề đổi testdata — nên một bộ test được tải <b>một lần cho cả nghìn bài nộp
     * cùng đề</b>. Nó không tiêu phần nào của ngân sách 2 giây.
     *
     * <h2>★ Đây là đường ra của NỘI DUNG TESTCASE ẨN — đọc bất biến #1 trước khi sửa</h2>
     * Ba lớp giữ nó, và cả ba đều cần thiết:
     * <ol>
     *   <li><b>Không nằm dưới {@code /api/v1/}</b>, nên Cloudflare Tunnel không publish nó.
     *       Không có lối vào từ internet.</li>
     *   <li>Xác thực bằng shared secret, không bằng JWT người dùng — không có vai trò nào của
     *       hệ thống mở được đường này.</li>
     *   <li>Khoá là <b>hash nội dung</b>, không phải id đề hay số thứ tự test. Không đoán
     *       được, không duyệt được, và biết một hash không cho biết nó thuộc đề nào.</li>
     * </ol>
     *
     * <p>Người gọi ghép {@code "/" + sha256} vào hằng này. Đừng log giá trị trả về.
     */
    public static final String TESTDATA = BASE + "/testdata";

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
