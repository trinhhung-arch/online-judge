package dev.oj.judging.api;

import dev.oj.contract.Verdict;
import dev.oj.judging.api.VerdictExplainer.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictExplainerTest {

    @ParameterizedTest
    @EnumSource(Verdict.class)
    @DisplayName("★ U3 — bảy trên bảy verdict đều nói được lý do")
    void bay_tren_bay_giai_thich_duoc(Verdict verdict) {
        String text = VerdictExplainer.explain(verdict, Facts.none());
        assertThat(text).isNotBlank();
        assertThat(text.length())
                .as("một câu giải thích dưới 20 ký tự thì chỉ là chữ viết tắt viết dài ra")
                .isGreaterThan(20);
    }

    @Test
    @DisplayName("★ TLE nói rõ ĐO ĐƯỢC BAO NHIÊU trên GIỚI HẠN BAO NHIÊU")
    void tle_noi_hai_con_so() {
        String text = VerdictExplainer.explain(Verdict.TLE,
                new Facts(2030, 2000, null, null, null, null, null));
        assertThat(text).contains("2.03s", "2.00s");
    }

    /**
     * Vượt sát hạn và vượt gấp nhiều lần cần hai lời khuyên NGƯỢC NHAU: một bên tối ưu hằng
     * số, một bên phải đổi thuật toán. Đưa nhầm lời khuyên là bảo người ta đi sửa thứ không
     * hỏng — mất buổi tối của họ, và họ vẫn TLE.
     */
    @Test
    @DisplayName("★ TLE sát hạn và TLE gấp nhiều lần cho lời khuyên khác nhau")
    void tle_sat_han_khac_tle_vuot_xa() {
        String satHan = VerdictExplainer.explain(Verdict.TLE,
                new Facts(2100, 2000, null, null, null, null, null));
        String vuotXa = VerdictExplainer.explain(Verdict.TLE,
                new Facts(9000, 2000, null, null, null, null, null));

        assertThat(satHan).contains("hằng số");
        assertThat(vuotXa).contains("độ phức tạp");
        assertThat(satHan).isNotEqualTo(vuotXa);
    }

    @Test
    @DisplayName("★ RE dịch tín hiệu sang câu người đọc được")
    void re_dich_tin_hieu() {
        assertThat(explainRe("SG exit=0 signal=11 cpu=3ms")).contains("SIGSEGV", "ngoài phạm vi");
        assertThat(explainRe("SG signal=8 cpu=1ms")).contains("SIGFPE", "chia cho 0");
        assertThat(explainRe("SG signal=6 cpu=1ms")).contains("SIGABRT");
    }

    /**
     * Tín hiệu lạ thì nói thẳng là lạ. Đoán bừa một nguyên nhân còn tệ hơn không đoán, vì
     * thí sinh sẽ đi sửa đúng thứ không hỏng — cùng một nguyên tắc với "mã isolate lạ → IE,
     * không map bừa sang RE" ({@code oj-worker/CLAUDE.md} mục 6).
     */
    @Test
    @DisplayName("tín hiệu lạ không bị đoán bừa thành một nguyên nhân cụ thể")
    void tin_hieu_la_khong_doan_bua() {
        assertThat(explainRe("SG signal=31 cpu=1ms"))
                .contains("tín hiệu 31")
                .doesNotContain("SIGSEGV", "mảng");
    }

    @Test
    @DisplayName("RE không có tín hiệu thì giải thích theo mã thoát")
    void re_khong_co_tin_hieu() {
        assertThat(VerdictExplainer.explain(Verdict.RE, Facts.none()))
                .contains("mã thoát khác 0");
    }

    @Test
    @DisplayName("★ feedback_level NONE: không lộ số thứ tự test, và câu chữ tự đổi giọng")
    void wa_ton_trong_feedback_level() {
        String an = VerdictExplainer.explain(Verdict.WA, Facts.none());
        String hien = VerdictExplainer.explain(Verdict.WA,
                new Facts(null, null, null, null, 7, null, null));

        assertThat(an).doesNotContain("test 7").contains("không công bố");
        assertThat(hien).contains("test 7");
    }

    /**
     * ★ Bất biến #1 ở tầng trình bày. {@code isolateStatus} chứa đường dẫn bên trong box và
     * thông báo nguyên văn của isolate; nó vào đây CHỈ để rút một con số tín hiệu.
     */
    @Test
    @DisplayName("★ SEC3 — isolateStatus không bao giờ lọt nguyên văn ra câu giải thích")
    void khong_lot_isolate_status_nguyen_van() {
        String doc = "XX message:open(\"/var/local/lib/isolate/3/box/input.txt\") signal=11";
        for (Verdict verdict : Verdict.values()) {
            String text = VerdictExplainer.explain(verdict,
                    new Facts(1, 2, 3, 4, 5, 6, doc));
            assertThat(text)
                    .as("verdict %s làm rò đường dẫn trong box", verdict)
                    .doesNotContain("/box", "isolate", "input.txt");
        }
    }

    @Test
    @DisplayName("★ IE nói rõ ĐÂY KHÔNG PHẢI LỖI CỦA BẠN, kèm mã sự cố tra được")
    void ie_khong_do_loi_thi_sinh() {
        String text = VerdictExplainer.explain(Verdict.IE,
                new Facts(null, null, null, null, null, null, "XX message:box hỏng"));
        assertThat(text).contains("không phải lỗi bài của bạn", "IE-");
    }

    /** Cùng một sự cố phải cho cùng một mã, nếu không thì mã đó tra được cái gì? */
    @Test
    @DisplayName("mã sự cố ổn định giữa hai lần gọi")
    void ma_su_co_on_dinh() {
        Facts facts = new Facts(null, null, null, null, null, null, "XX message:cùng một lỗi");
        assertThat(VerdictExplainer.explain(Verdict.IE, facts))
                .isEqualTo(VerdictExplainer.explain(Verdict.IE, facts));
    }

    private static String explainRe(String isolateStatus) {
        return VerdictExplainer.explain(Verdict.RE,
                new Facts(null, null, null, null, null, null, isolateStatus));
    }
}
