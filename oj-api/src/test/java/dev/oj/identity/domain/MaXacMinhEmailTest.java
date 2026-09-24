package dev.oj.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MaXacMinhEmail} — FR-AUTH-09 (V13). Java thuần, không Spring, không database.
 *
 * <p>Ba ca đầu canh ba thứ mà một lỗi ở đó <b>không làm test nào khác đỏ</b>: mã mất số 0
 * đứng đầu, mã không đủ ngẫu nhiên, và mã thô lọt vào {@code toString()}.
 */
class MaXacMinhEmailTest {

    @Test
    @DisplayName("★ mã LUÔN đủ 6 chữ số — kể cả khi nó bắt đầu bằng 0")
    void luon_du_sau_chu_so() {
        // 2 000 lượt: xác suất không gặp một mã nào nhỏ hơn 100000 là (0,9)^2000 ≈ 10^-92.
        // Nói cách khác, nếu phần đệm số 0 hỏng thì ca này đỏ ở mọi lần chạy, không phải
        // "thỉnh thoảng" — và một ca ngẫu nhiên chỉ có giá trị khi nó chắc chắn như thế.
        for (int i = 0; i < 2_000; i++) {
            String ma = MaXacMinhEmail.sinh().giaTriTho();
            assertThat(ma)
                    .as("mã mất số 0 đứng đầu thì người dùng gõ 5 chữ số và luôn bị từ chối")
                    .hasSize(MaXacMinhEmail.SO_CHU_SO)
                    .containsOnlyDigits();
        }
    }

    @Test
    @DisplayName("★ 1 000 mã liên tiếp không lặp lại quá vài lần — nguồn ngẫu nhiên có chạy")
    void du_ngau_nhien() {
        Set<String> daThay = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            daThay.add(MaXacMinhEmail.sinh().giaTriTho());
        }

        // Với một triệu khả năng, 1 000 lượt rút kỳ vọng ~999,5 giá trị khác nhau (nghịch lý
        // ngày sinh). Ngưỡng 990 rộng rãi so với kỳ vọng nhưng vẫn bắt được kiểu hỏng thật sự
        // đáng sợ: một nguồn ngẫu nhiên bị thay bằng hằng số, bằng bộ đếm, hay bằng một
        // `Random` gieo cùng một hạt mỗi lần khởi động.
        assertThat(daThay)
                .as("mã trùng nhau nhiều nghĩa là nguồn ngẫu nhiên hỏng — và một mã đoán "
                        + "được thì ba hàng rào của V13 cũng không cứu nổi")
                .hasSizeGreaterThan(990);
    }

    @Test
    @DisplayName("★ toString() không in mã thô — bất biến #9")
    void khong_in_ma_tho() {
        MaXacMinhEmail ma = MaXacMinhEmail.sinh();

        assertThat(ma.toString())
                .doesNotContain(ma.giaTriTho())
                .contains(ma.sha256Hex().substring(0, 8));
    }

    @Test
    @DisplayName("băm là SHA-256 tất định: cùng mã ra cùng chuỗi 64 ký tự hex")
    void bam_tat_dinh() {
        assertThat(MaXacMinhEmail.bam("012345"))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(MaXacMinhEmail.bam("012345"))
                .isNotEqualTo(MaXacMinhEmail.bam("012346"));
    }

    @Test
    @DisplayName("khop() so bản băm đã lưu với mã người dùng gõ")
    void khop_dung_ma() {
        MaXacMinhEmail ma = MaXacMinhEmail.sinh();

        assertThat(MaXacMinhEmail.khop(ma.sha256Hex(), ma.giaTriTho())).isTrue();
        assertThat(MaXacMinhEmail.khop(ma.sha256Hex(), "000000"))
                .as("mã khác thì không khớp — trừ khi chính nó là 000000")
                .isEqualTo("000000".equals(ma.giaTriTho()));
    }

    @Test
    @DisplayName("khop() chịu được null ở cả hai vế thay vì ném NPE")
    void khop_chiu_duoc_null() {
        assertThat(MaXacMinhEmail.khop(null, "123456")).isFalse();
        assertThat(MaXacMinhEmail.khop("a".repeat(64), null)).isFalse();
        assertThat(MaXacMinhEmail.khop(null, null)).isFalse();
    }

    @Test
    @DisplayName("★ chuanHoa cắt khoảng trắng — người ta DÁN mã từ thư, không gõ lại")
    void chuan_hoa_cat_khoang_trang() {
        // Chép từ một lá thư gần như luôn kéo theo khoảng trắng hoặc một dấu xuống dòng.
        // Không cắt thì mã đúng bị đếm là một lượt gõ sai, và năm lần dán là mã chết —
        // một hàng rào chống dò quay ra phạt đúng người dùng thật.
        assertThat(MaXacMinhEmail.chuanHoa("  123456\n")).isEqualTo("123456");
        assertThat(MaXacMinhEmail.chuanHoa("123456")).isEqualTo("123456");
        assertThat(MaXacMinhEmail.chuanHoa("   ")).isEmpty();
        assertThat(MaXacMinhEmail.chuanHoa(null)).isNull();
    }
}
