package dev.oj.judging.api;

import dev.oj.contract.Verdict;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ Bước 3.11 · U3 — <b>bảy trên bảy verdict phải nói được lý do</b>.
 *
 * <h2>Vì sao một chữ viết tắt không phải là một câu trả lời</h2>
 * {@code RE} nói với người mới học rằng "có gì đó sai" và không nói gì thêm. Họ đọc lại
 * thuật toán — vốn đúng — trong bốn mươi phút, rồi bỏ cuộc. Cùng lượng thông tin ấy viết
 * thành "bị tín hiệu SIGSEGV — thường do truy cập mảng ngoài phạm vi hoặc đệ quy quá sâu"
 * thì họ tìm ra trong bốn phút. Đây là {@code nfrplan.md} 6.2, và nó là khác biệt giữa một
 * công cụ dạy được và một công cụ chấm điểm.
 *
 * <h2>⚠️ Hai điều class này TUYỆT ĐỐI không được làm</h2>
 * <ol>
 *   <li><b>Không chạm vào nội dung testcase.</b> Nó không nhận nội dung nào, và không có
 *       tham số nào mang nội dung được — bất biến #1 ép ở tầng chữ ký hàm.</li>
 *   <li><b>Không in nguyên văn {@code isolateStatus}.</b> Chuỗi đó chứa đường dẫn bên trong
 *       box, và {@code oj-worker/CLAUDE.md} mục 7 cấm để chúng ra ngoài. Ở đây nó chỉ được
 *       <i>đọc</i> để rút ra một con số tín hiệu, rồi bị bỏ đi.</li>
 * </ol>
 *
 * <h2>{@code failedTestOrdinal} phải được LỌC TRƯỚC khi tới đây</h2>
 * Class này in ra con số nó nhận được. Việc quyết định người dùng <i>có được thấy</i> con số
 * ấy hay không là của {@code FeedbackLevel}, và phải xảy ra ở tầng gọi. Truyền {@code null}
 * nghĩa là "đề không cho lộ" — câu giải thích tự đổi giọng, không cần thêm cờ nào.
 */
public final class VerdictExplainer {

    /** {@code IsolateMeta.diagnostic()} viết {@code "SG exit=1 signal=11 cpu=3ms …"}. */
    private static final Pattern SIGNAL = Pattern.compile("signal=(\\d+)");

    private VerdictExplainer() {
    }

    /**
     * Mọi thứ cần để giải thích một verdict — và <b>không có gì hơn thế</b>.
     *
     * @param timeLimitMs   giới hạn của đề, đã nhân hệ số ngôn ngữ. Có nó thì TLE nói được
     *                      "2.03s / 2.00s" thay vì "quá giờ"; thiếu nó thì câu giải thích
     *                      vẫn đúng, chỉ nghèo hơn
     * @param failedTestOrdinal {@code null} = đề không cho lộ (feedback_level NONE) hoặc
     *                      không có test nào sai
     * @param isolateStatus chỉ dùng để rút mã tín hiệu. <b>Không bao giờ được in ra</b>
     */
    public record Facts(
            Integer timeMs,
            Integer timeLimitMs,
            Integer memoryKb,
            Integer memoryLimitKb,
            Integer failedTestOrdinal,
            Integer testsRun,
            String isolateStatus) {

        public static Facts none() {
            return new Facts(null, null, null, null, null, null, null);
        }
    }

    /** Một câu, viết cho người vừa nhận verdict, không phải cho người vận hành. */
    public static String explain(Verdict verdict, Facts facts) {
        return switch (verdict) {
            case AC -> accepted(facts);
            case WA -> wrongAnswer(facts);
            case TLE -> timeLimit(facts);
            case MLE -> memoryLimit(facts);
            case RE -> runtimeError(facts);
            case CE -> "Không biên dịch được. Xem log của trình biên dịch bên dưới — dòng "
                    + "đầu tiên có chữ 'error' thường là nguyên nhân thật, các dòng sau "
                    + "phần lớn là hệ quả của nó.";
            case IE -> internalError(facts);
        };
    }

    private static String accepted(Facts facts) {
        // Câu nền phải tự nó có nghĩa, kể cả khi không có số đo nào đi kèm: "Đúng toàn bộ
        // test." thì đúng nhưng rỗng — nó chỉ là chữ AC viết dài ra. Nói rõ "MỌI dữ liệu
        // chấm của đề" mới trả lời được câu hỏi thí sinh thật sự có: bài mình đã qua những
        // gì, và test ẩn có được chạy không.
        StringBuilder sb = new StringBuilder("Đúng trên toàn bộ dữ liệu chấm của đề.");
        if (facts.timeMs() != null) {
            sb.append(' ').append(RuntimeFormatter.roundMs(facts.timeMs())).append("ms");
            if (facts.memoryKb() != null) {
                sb.append(" · ").append(RuntimeFormatter.memory(facts.memoryKb()));
            }
            sb.append(" (").append(RuntimeFormatter.MEASUREMENT_NOTE).append(").");
        }
        return sb.toString();
    }

    private static String wrongAnswer(Facts facts) {
        if (facts.failedTestOrdinal() == null) {
            // Thể thức ICPC: biết mình sai là đủ, biết sai ở đâu thì thành dò đáp án.
            return "Sai đáp án. Đề này không công bố test nào sai — hãy tự dựng thêm dữ liệu "
                    + "thử, đặc biệt là các trường hợp biên.";
        }
        return "Sai đáp án ở test " + facts.failedTestOrdinal()
                + ". Nội dung test là dữ liệu ẩn của đề, nhưng số thứ tự thường đủ để đoán: "
                + "test đầu hay là mẫu, test cuối hay là dữ liệu lớn nhất.";
    }

    private static String timeLimit(Facts facts) {
        String measured = RuntimeFormatter.seconds(facts.timeMs());
        String limit = RuntimeFormatter.seconds(facts.timeLimitMs());
        String head = "Vượt giới hạn thời gian: " + measured + " / " + limit + ".";

        // Vượt sát hạn và vượt gấp nhiều lần là HAI bài toán khác nhau, và lời khuyên cho
        // chúng ngược nhau: một bên tối ưu hằng số, một bên phải đổi thuật toán.
        if (facts.timeMs() != null && facts.timeLimitMs() != null
                && facts.timeMs() < facts.timeLimitMs() * 1.5) {
            return head + " Chỉ vượt một chút — thường là tối ưu hằng số đủ cứu: tắt "
                    + "đồng bộ cin/cout, tránh cấp phát trong vòng lặp, dùng mảng thay map.";
        }
        return head + " Vượt xa hạn thì hầu như luôn là độ phức tạp chứ không phải hằng số — "
                + "đọc lại giới hạn dữ liệu của đề và ước lượng số phép tính.";
    }

    private static String memoryLimit(Facts facts) {
        return "Vượt giới hạn bộ nhớ: " + RuntimeFormatter.memory(facts.memoryKb())
                + " / " + RuntimeFormatter.memory(facts.memoryLimitKb())
                + ". Thường là mảng khai báo theo giới hạn lớn nhất trong khi đề không cần, "
                + "hoặc đệ quy quá sâu làm ngăn xếp phình ra.";
    }

    /**
     * Ánh xạ tín hiệu POSIX sang câu người đọc được.
     *
     * <p>Chỉ những tín hiệu <b>thật sự</b> xảy ra với bài thi mới có câu riêng. Tín hiệu lạ
     * thì nói thẳng là lạ — đoán bừa một nguyên nhân còn tệ hơn không đoán, vì thí sinh sẽ
     * đi sửa đúng thứ không hỏng.
     */
    private static String runtimeError(Facts facts) {
        Integer signal = signalOf(facts.isolateStatus());
        if (signal == null) {
            return "Chương trình kết thúc bất thường (mã thoát khác 0). Nếu bạn có "
                    + "'return 1' hay 'exit(1)' ở nhánh lỗi thì đó là nó — máy chấm coi mọi "
                    + "mã thoát khác 0 là lỗi chạy.";
        }
        String cause = switch (signal) {
            case 4 -> "SIGILL — lệnh máy không hợp lệ, thường là hỏng con trỏ hàm";
            case 6 -> "SIGABRT — assert thất bại, hoặc một exception không ai bắt "
                    + "(bad_alloc, vector::at ngoài phạm vi)";
            case 8 -> "SIGFPE — chia cho 0, hoặc chia INT_MIN cho -1";
            case 11 -> "SIGSEGV — thường do truy cập mảng ngoài phạm vi, con trỏ null, "
                    + "hoặc đệ quy quá sâu làm tràn ngăn xếp";
            case 25 -> "SIGXFSZ — chương trình ghi ra file vượt giới hạn cho phép";
            default -> "tín hiệu " + signal;
        };
        return "Chương trình bị dừng bởi " + cause + ".";
    }

    /**
     * {@code IE} là verdict duy nhất <b>không phải lỗi của thí sinh</b>, và câu chữ phải nói
     * rõ điều đó. Để họ nghĩ mình sai trong khi máy chấm hỏng là cách nhanh nhất làm mất
     * lòng tin vào cả hệ thống — mà lòng tin thì đúng là thứ một OJ bán.
     */
    private static String internalError(Facts facts) {
        String code = incidentCode(facts.isolateStatus());
        return "Sự cố máy chấm, không phải lỗi bài của bạn. Hệ thống sẽ tự chấm lại (tối đa "
                + "2 lần). Nếu vẫn lặp lại, báo ban tổ chức kèm mã sự cố: " + code + ".";
    }

    private static Integer signalOf(String isolateStatus) {
        if (isolateStatus == null) {
            return null;
        }
        Matcher matcher = SIGNAL.matcher(isolateStatus);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * Mã sự cố ngắn, ổn định, <b>không mang nội dung</b>: đủ để người vận hành tra ra đúng
     * dòng {@code judge_runs}, không đủ để lộ bất cứ gì.
     */
    private static String incidentCode(String isolateStatus) {
        if (isolateStatus == null || isolateStatus.isBlank()) {
            return "IE-UNKNOWN";
        }
        return "IE-" + String.format("%08X", isolateStatus.hashCode());
    }
}
