package dev.oj.judging.api.internal;

import dev.oj.contract.JudgeEndpoints;
import dev.oj.problems.application.published.TestdataBytes;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Optional;

/**
 * ★★ {@code GET /internal/judge/testdata/{sha256}} — <b>đường ra duy nhất của nội dung
 * testcase ẩn ra khỏi API.</b> Đọc bất biến #1 trước khi sửa một dòng nào ở đây.
 *
 * <h2>Mắt xích này từng thiếu, và hệ thống vẫn "xanh" suốt bốn mốc</h2>
 * {@code TestcaseMetaDto} cố ý không mang nội dung — chỉ có hash. Javadoc của nó nói worker
 * tải nội dung "từ MinIO", còn {@code oj-worker/CLAUDE.md} mục 3 cấm worker có MinIO client.
 * Hai câu mâu thuẫn nhau, nên <b>không ai viết đoạn vận chuyển</b>: hiện thực
 * {@code TestdataSource} duy nhất đọc một thư mục cục bộ mà không gì đổ dữ liệu vào.
 *
 * <p>Không một test nào đỏ, vì mọi test của worker tự đổ testdata vào thư mục ấy trước khi
 * chạy — chúng kiểm đúng thứ chúng nhắm tới (sandbox, checker, chấm điểm) và mù với câu hỏi
 * "dữ liệu từ đâu tới". Triệu chứng thật là <b>mọi bài nộp trả {@code IE}</b> ngay khi
 * testdata được nạp qua API, và nó chỉ lộ ra khi chạy tay cả hai tiến trình.
 *
 * <h2>Vì sao qua API chứ không cho worker một MinIO client</h2>
 * Bất biến #3: worker chỉ biết những đường dẫn trong {@code JudgeEndpoints}. Giữ nguyên câu đó
 * đáng giá hơn một lượt round-trip — worker không có credential của hạ tầng nào, nên một máy
 * chấm bị chiếm không mở ra được kho testdata của mọi đề khác. Trên đúng cái máy chạy mã của
 * người lạ thì đó không phải chi tiết nhỏ.
 *
 * <p>Chi phí gần bằng không: worker cache theo hash ({@code ContentAddressedCache}), và hash
 * chỉ đổi khi đề đổi testdata. Một bộ test đi qua API <b>một lần cho cả nghìn bài nộp cùng
 * đề</b>. Đường này không nằm trên ngân sách 2 giây.
 *
 * <h2>Bốn lớp giữ nội dung không rò ra ngoài</h2>
 * <ol>
 *   <li><b>Ngoài {@code /api/v1/}</b> — Cloudflare Tunnel chỉ publish tiền tố đó, nên không
 *       có lối vào từ internet.</li>
 *   <li><b>{@code InternalSecretFilter}</b> đăng ký trên {@code BASE + "/*"}, nên nó bọc
 *       đường này với <b>mọi</b> HTTP method mà không phải khai báo gì thêm.</li>
 *   <li><b>Khoá là hash nội dung</b>, không phải id đề hay số thứ tự test: không đoán được,
 *       không duyệt được, và biết một hash không cho biết nó thuộc đề nào.</li>
 *   <li><b>404 cho hash sai định dạng</b> — {@code TestdataKeys.hopLe} chặn ở
 *       {@code StoreTestdataBytes}, trước khi chuỗi chạm tới kho.</li>
 * </ol>
 *
 * <h2>Không log gì về nội dung, kể cả kích thước</h2>
 * Kích thước của một testcase là thông tin: nó phân biệt được test nhỏ với test lớn, và trong
 * một bộ test được sắp theo độ khó thì đó là một tín hiệu. Ở đây không có dòng log nào — thành
 * công thì im lặng, thất bại thì {@code GlobalExceptionHandler} lo, và cả hai đều không chạm
 * vào nội dung.
 */
@RestController
@RequestMapping(JudgeEndpoints.TESTDATA)
public class InternalTestdataController {

    private final TestdataBytes testdata;

    public InternalTestdataController(TestdataBytes testdata) {
        this.testdata = testdata;
    }

    /**
     * {@code InputStreamResource} chứ không phải {@code byte[]}: Spring stream thẳng ra
     * socket. Một đối tượng testdata có thể tới vài trăm MB, và đọc nó vào heap là biến một
     * lượt tải của worker thành một cách làm sập API.
     *
     * @return 200 kèm nội dung, hoặc <b>404</b> nếu hash không có hoặc sai định dạng — hai
     *         trường hợp gộp làm một, cùng lý do với {@code ProblemNotFoundException}: phân
     *         biệt chúng là xác nhận "hash này đúng định dạng nhưng không tồn tại", và đó là
     *         một tín hiệu cho người đang dò
     */
    @GetMapping(path = "/{sha256}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputStreamResource> tai(@PathVariable String sha256) {
        Optional<InputStream> noiDung = testdata.doc(sha256);
        return noiDung.map(in -> ResponseEntity.ok(new InputStreamResource(in)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
