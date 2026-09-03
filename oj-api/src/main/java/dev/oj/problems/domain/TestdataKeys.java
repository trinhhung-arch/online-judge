package dev.oj.problems.domain;

/**
 * Cách một nội dung testdata được đánh địa chỉ. Java thuần.
 *
 * <h2>Vì sao ở {@code domain} chứ không ở trong {@code MinioTestdataStore}</h2>
 * Hai lý do, và lý do thứ hai mới là lý do thật.
 *
 * <ol>
 *   <li><b>Cách bố trí khoá là một quyết định về dữ liệu, không phải về MinIO.</b> Ngày đổi
 *       sang S3 hay sang một thư mục trên đĩa, khoá phải giữ nguyên — nếu không thì mọi nội
 *       dung đã lưu trở thành không tìm lại được.</li>
 *   <li><b>Luật ArchUnit 5b cấm dựng chuỗi động trong {@code infrastructure}</b>, và bước
 *       {@code grep} của CI thô tới mức bắt cả một phép ghép khoá vô hại. Đó là hành vi
 *       <i>đúng</i> — nới bộ lọc ra để cho qua chỗ này là bỏ hàng rào cuối cùng chặn một mệnh
 *       đề {@code WHERE} ghép từ dữ liệu người dùng (bất biến #5).</li>
 * </ol>
 *
 * <p>Nên thay vì nới luật, phép ghép được chuyển về nơi nó vốn thuộc về. Cùng cách xử lý đã
 * dùng ở {@code RedisSubmissionEventBus} tại M3, và cũng cho một kết quả tốt hơn về mặt tầng lớp.
 */
public final class TestdataKeys {

    private static final String TIEN_TO = "testdata/";

    private TestdataKeys() {
    }

    /**
     * {@code testdata/ab/ab3f9c...} — hai ký tự đầu của hash thành một tầng thư mục.
     *
     * <p>Không phải để đẹp: một thư mục phẳng với hàng trăm nghìn đối tượng làm mọi công cụ
     * liệt kê chậm tới mức không dùng được — kể cả {@code mc ls} lúc đang đi tìm sự cố. Đây là
     * khuôn git dùng cho {@code objects/}, và vì đúng lý do đó.
     */
    public static String khoa(String sha256) {
        if (!hopLe(sha256)) {
            throw new IllegalArgumentException("sha256 phải là 64 ký tự hex thường");
        }
        return TIEN_TO + sha256.substring(0, 2) + "/" + sha256;
    }

    /**
     * ★ Đúng 64 ký tự {@code [0-9a-f]}, không gì khác.
     *
     * <h2>Vì sao kiểm ký tự chứ không chỉ kiểm độ dài</h2>
     * Bản đầu chỉ kiểm {@code length() != 64}, và điều đó đủ khi hash chỉ đến từ code của
     * chính ta. Từ khi có {@code JudgeEndpoints.TESTDATA}, nó đến từ một <b>path variable</b>
     * — tức là từ mạng — và được ghép thẳng vào khoá đối tượng của kho.
     *
     * <p>Một chuỗi 64 ký tự chứa {@code ../} vẫn qua được phép kiểm độ dài. Nó sẽ không tìm
     * thấy gì trong MinIO (khoá không khớp), nhưng đó là <i>may</i>, không phải <i>thiết kế</i>
     * — và ngày kho đổi sang một hệ thống tệp thật thì may hết.
     *
     * <p>Chỉ nhận chữ thường: hash do ta sinh ra luôn là chữ thường, nên chấp nhận chữ hoa là
     * mở ra hai khoá cho cùng một nội dung, và cache content-addressed mất tính duy nhất.
     */
    public static boolean hopLe(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = sha256.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
