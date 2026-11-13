package dev.oj.judging.api;

import dev.oj.contract.Verdict;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VerdictExplainer {

    private static final Pattern SIGNAL = Pattern.compile("signal=(\\d+)");

    private VerdictExplainer() {
    }

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

    private static String internalError(Facts facts) {
        String code = incidentCode(facts.isolateStatus());
        return "Sự cố máy chấm, không phải lỗi bài của bạn. Hệ thống đã tự chấm lại mà vẫn gặp "
                + "sự cố. Hãy nộp lại, hoặc báo ban tổ chức kèm mã sự cố: " + code + ".";
    }

    private static Integer signalOf(String isolateStatus) {
        if (isolateStatus == null) {
            return null;
        }
        Matcher matcher = SIGNAL.matcher(isolateStatus);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String incidentCode(String isolateStatus) {
        if (isolateStatus == null || isolateStatus.isBlank()) {
            return "IE-UNKNOWN";
        }
        return "IE-" + String.format("%08X", isolateStatus.hashCode());
    }
}
