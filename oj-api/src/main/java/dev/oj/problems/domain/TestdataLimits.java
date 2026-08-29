package dev.oj.problems.domain;

import java.util.regex.Pattern;

/**
 * Giới hạn của một gói testdata — FR-PROB-03. <b>Nằm trong đặc tả, không phải trong config
 * ẩn</b> ({@code frplan.md} mục 3, cách viết lại FR-PROB-03).
 *
 * <h2>Vì sao các con số này KHÔNG ở {@code AppProperties}</h2>
 * Vì chúng không phải ngưỡng vận hành mà là <b>một phần của hợp đồng với người ra đề</b>:
 * chúng được công bố, được viết trong thông báo lỗi, và đổi chúng là đổi thứ người dùng đã
 * được hứa. {@code CLAUDE.md} mục 5.4 xếp "giới hạn ZIP 200MB" vào danh sách phải hỏi người.
 *
 * <p>Chúng cũng có bản sao trong CHECK của V2 ({@code test_count BETWEEN 1 AND 1000},
 * {@code total_bytes <= 2147483648}) — cùng khuôn với {@link ProblemRules}: bản ở đây cho câu
 * tiếng Việt, bản trong database là chốt thật.
 */
public final class TestdataLimits {

    /** ≤ 200MB nén. */
    public static final long MAX_ZIP_BYTES = 200L * 1024 * 1024;

    /** ≤ 2GB sau giải nén. Khớp {@code CHECK (total_bytes <= 2147483648)}. */
    public static final long MAX_GIAI_NEN_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Tỉ lệ nén ≤ 100:1 — <b>chốt chống zip bomb</b>.
     *
     * <p>Một file 200MB toàn số 0 giải nén ra hàng trăm GB. Trần dung lượng một mình không
     * chặn được vì nó chỉ phát hiện sau khi đã ghi ra ngần ấy; trần tỉ lệ phát hiện <i>trong
     * lúc đọc</i>, sau vài megabyte đầu tiên.
     */
    public static final int MAX_TI_LE_NEN = 100;

    /** ≤ 1000 testcase. Khớp {@code CHECK (ordinal BETWEEN 1 AND 1000)}. */
    public static final int MAX_TEST = 1000;

    /** Khớp {@code CHECK (octet_length(input_text) <= 65536)} của {@code sample_testcase_contents}. */
    public static final int MAX_SAMPLE_BYTES = 65_536;

    /**
     * Trần cho MỘT file test, để job nạp dữ liệu không phải giữ cả gigabyte trong RAM.
     *
     * <p>Không có trong đặc tả gốc, và nó là một ngưỡng <b>kỹ thuật</b> chứ không phải một lời
     * hứa với người ra đề: nó tồn tại vì mỗi file được đệm vào bộ nhớ để băm rồi đẩy lên kho.
     * 64MB cho một file test đã là rộng rãi — bộ test lớn nhất của một đề thường vài trăm KB
     * mỗi file.
     */
    public static final int MAX_MOT_FILE_BYTES = 64 * 1024 * 1024;

    /**
     * ★ Tên file được chấp nhận trong gói. Danh sách <b>cho phép</b>, không phải danh sách cấm.
     *
     * <p>Một danh sách cấm ({@code ..}, {@code /} đầu, {@code \\}, ký tự null, tên NTFS đặc
     * biệt...) là một cuộc đua mà bên phòng thủ luôn chậm hơn. Một danh sách cho phép trả lời
     * dứt điểm: chỉ chữ, số, chấm, gạch dưới, gạch ngang — nên {@code ../../etc/passwd},
     * {@code /etc/passwd} và {@code C:\\Windows} đều không khớp mà không cần nêu tên chúng.
     */
    public static final Pattern TEN_FILE_TEST =
            Pattern.compile("^tests/[A-Za-z0-9_.-]{1,64}\\.(in|out)$");

    /** Mục lục bắt buộc, ở gốc gói. */
    public static final String MANIFEST = "problem.yaml";

    private TestdataLimits() {
    }
}
