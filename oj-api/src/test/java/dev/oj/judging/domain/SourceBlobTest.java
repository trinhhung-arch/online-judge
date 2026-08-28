package dev.oj.judging.domain;

import dev.oj.contract.Sha256;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Giới hạn 64KB (FR-SUB-01) và khoá khử trùng lặp. JUnit trần, không Spring context. */
class SourceBlobTest {

    private static final String SOURCE = "#include <bits/stdc++.h>\nint main(){return 0;}";

    @Test
    void of_tinh_hash_va_kich_thuoc_theo_byte() {
        SourceBlob blob = SourceBlob.of(SOURCE);

        assertThat(blob.sha256()).isEqualTo(Sha256.hexOf(SOURCE));
        assertThat(blob.byteSize()).isEqualTo(SOURCE.getBytes(StandardCharsets.UTF_8).length);
        assertThat(blob.content()).isEqualTo(SOURCE);
    }

    /** Cùng source thì cùng khoá: khử trùng lặp DB, cache biên dịch, cache AI review. */
    @Test
    void cung_noi_dung_thi_cung_khoa() {
        assertThat(SourceBlob.of(SOURCE).sha256()).isEqualTo(SourceBlob.of(SOURCE).sha256());
        assertThat(SourceBlob.of(SOURCE).sha256()).isNotEqualTo(SourceBlob.of(SOURCE + " ").sha256());
    }

    @Test
    void dung_64KB_thi_nhan_them_mot_byte_thi_tu_choi() {
        String vua_du = "a".repeat(DomainRules.MAX_SOURCE_BYTES);
        String qua_mot_byte = "a".repeat(DomainRules.MAX_SOURCE_BYTES + 1);

        assertThat(SourceBlob.of(vua_du).byteSize()).isEqualTo(DomainRules.MAX_SOURCE_BYTES);

        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> SourceBlob.of(qua_mot_byte))
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID);
                    assertThat(e.code()).isEqualTo("submission.source_too_large");
                    // Câu ra người dùng phải nói cả giới hạn lẫn kích thước thật của họ.
                    assertThat(e.publicMessage()).contains(
                            String.valueOf(DomainRules.MAX_SOURCE_BYTES + 1),
                            String.valueOf(DomainRules.MAX_SOURCE_BYTES));
                });
    }

    /**
     * Đếm byte chứ không đếm ký tự. Một bài toàn comment tiếng Việt chạm trần byte trước khi
     * chạm trần ký tự — đếm bằng {@code length()} là để lọt một bài gấp ba giới hạn.
     */
    @Test
    void tieng_viet_duoc_dem_theo_byte_khong_theo_ky_tu() {
        String tieng_viet = "ộ".repeat(DomainRules.MAX_SOURCE_BYTES / 2);   // 3 byte mỗi ký tự

        assertThat(tieng_viet.length()).isLessThan(DomainRules.MAX_SOURCE_BYTES);
        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> SourceBlob.of(tieng_viet));
    }

    @Test
    void source_rong_bi_tu_choi_truoc_khi_ton_mot_luot_cham() {
        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> SourceBlob.of("   \n\t "))
                .satisfies(e -> assertThat(e.code()).isEqualTo("submission.empty_source"));
        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> SourceBlob.of(null));
    }

    /**
     * ★ Bất biến #9 — không bao giờ log mã nguồn người dùng.
     *
     * <p>Record sinh sẵn {@code toString()} in ra mọi thành phần, nên một dòng
     * {@code log.info("... {}", blob)} sẽ đổ toàn bộ bài của người dùng vào file log.
     */
    @Test
    void toString_khong_bao_gio_lo_noi_dung() {
        SourceBlob blob = SourceBlob.of(SOURCE);

        assertThat(blob.toString())
                .doesNotContain("include", "main", "return")
                .contains(blob.sha256())
                .contains(String.valueOf(blob.byteSize()));
    }

    @Test
    void byteSize_noi_doi_ve_content_thi_bi_bat() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceBlob(Sha256.hexOf(SOURCE), SOURCE, 5))
                .withMessageContaining("không khớp");
    }

    @Test
    void sha256_phai_dung_dang_64_ky_tu_hex_chu_thuong() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceBlob(Sha256.hexOf(SOURCE).toUpperCase(), SOURCE,
                        SOURCE.getBytes(StandardCharsets.UTF_8).length));
    }
}
