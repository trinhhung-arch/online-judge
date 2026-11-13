package dev.oj.it;

import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaiNopHttpIT extends HttpIT {

    private static final String NOP = "/api/v1/submissions";

    private record ThanHong(String ten, String than) {
    }

    private static final List<ThanHong> CAC_THAN_HONG = List.of(
            new ThanHong("JSON sai cú pháp", """
                    {"problemId": 1, "languageCode":
                    """),
            new ThanHong("problemId sai kiểu", """
                    {"problemId": "abc", "languageCode": "cpp20", "source": "int main(){}"}
                    """),
            new ThanHong("thiếu problemId", """
                    {"languageCode": "cpp20", "source": "int main(){}"}
                    """),
            new ThanHong("problemId = 0", """
                    {"problemId": 0, "languageCode": "cpp20", "source": "int main(){}"}
                    """),
            new ThanHong("languageCode rỗng", """
                    {"problemId": 1, "languageCode": "", "source": "int main(){}"}
                    """),
            new ThanHong("source rỗng", """
                    {"problemId": 1, "languageCode": "cpp20", "source": ""}
                    """));

    @Test
    @DisplayName("★ NH-05 · thân request hỏng → 400 có mã, không 500, không ghi gì và không tiêu lượt 10 giây")
    void than_request_hong() {
        for (ThanHong ca : CAC_THAN_HONG) {
            var res = goi(http.post().uri(NOP)
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ca.than()));

            assertThat(res.getStatusCode()).as(ca.ten()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) res.getBody().get("code")).as(ca.ten()).startsWith("request.");
        }

        assertThat(soBaiNop()).isZero();
        assertThat(goi(nop(bearerDev())).getStatusCode())
                .as("request hỏng không được tiêu mất lượt 10 giây của người nộp")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("★ XK-11 · LS-05 · lịch sử chỉ có bài của mình; bài bị ADMIN ẩn biến mất khỏi lịch sử và trang chi tiết của tác giả, hiện lại thì thấy lại")
    void bai_bi_an_va_bai_cua_nguoi_khac() {
        String toi = bearerDev();
        String admin = bearer(ADMIN_ID, "admin", Role.ADMIN);
        long cuaToi = idBai(goi(nop(toi)));
        long cuaNguoiKhac = idBai(goi(nop(bearer(SETTER_ID, "setter", Role.SETTER))));
        quenLuotNopVuaRoi(USER_ID);
        long seBiAn = idBai(goi(nop(toi)));

        assertThat(lichSu(toi)).as("mới nhất trước, không có bài của người khác")
                .containsExactly(seBiAn, cuaToi)
                .doesNotContain(cuaNguoiKhac);

        assertThat(goi(http.post().uri("/api/v1/admin/submissions/{id}/hide", seBiAn)
                .header(HttpHeaders.AUTHORIZATION, admin)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(lichSu(toi)).as("bài bị ẩn rời khỏi lịch sử của tác giả").containsExactly(cuaToi);
        var chiTiet = xem(seBiAn, toi);
        assertThat(chiTiet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chiTiet.getBody()).containsEntry("code", "submission.not_found");
        assertThat(xem(seBiAn, admin).getStatusCode())
                .as("ADMIN vẫn thấy bài đã ẩn").isEqualTo(HttpStatus.OK);
        assertThat(soBaiNop()).as("ẩn chứ không xoá").isEqualTo(3);

        assertThat(goi(http.post().uri("/api/v1/admin/submissions/{id}/unhide", seBiAn)
                .header(HttpHeaders.AUTHORIZATION, admin)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(lichSu(toi)).containsExactly(seBiAn, cuaToi);
        assertThat(xem(seBiAn, toi).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("★ LS-04 · lọc theo đề, verdict, ngôn ngữ trên Postgres thật; đi hết các trang không trùng, không sót")
    void loc_va_phan_trang_lich_su() {
        long deHai = jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                VALUES ('DE-HAI', 'Đề hai', 'x', :hash, 1000, 262144, 65536, 'token',
                        'ALL_OR_NOTHING', 'TEST_INDEX', 'PUBLISHED', :chu, now(), 1)
                RETURNING id
                """).param("hash", "e".repeat(64)).param("chu", SETTER_ID)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """).param("sha", "a".repeat(64)).update();

        long b1 = baiDaCham(PROBLEM_ID, "cpp20", "AC");
        long b2 = baiDaCham(PROBLEM_ID, "py311", "WA");
        long b3 = baiDaCham(deHai, "cpp20", "AC");
        long b4 = baiDaCham(deHai, "py311", "AC");
        long b5 = baiDaCham(PROBLEM_ID, "cpp20", "TLE");
        long b6 = baiDaCham(deHai, "cpp20", "WA");
        long b7 = baiDaCham(PROBLEM_ID, "py311", "AC");
        int python = jdbc.sql("""
                SELECT id FROM languages WHERE code = 'py311'
                """).query(Integer.class).single();
        String toi = bearerDev();

        assertThat(trang(toi, "?problemId=" + deHai).ids()).containsExactly(b6, b4, b3);
        assertThat(trang(toi, "?verdict=AC").ids()).containsExactly(b7, b4, b3, b1);
        assertThat(trang(toi, "?languageId=" + python).ids()).containsExactly(b7, b4, b2);
        assertThat(trang(toi, "?problemId=" + PROBLEM_ID + "&verdict=AC").ids())
                .containsExactly(b7, b1);

        List<Long> daDi = new java.util.ArrayList<>();
        String cursor = null;
        int soTrang = 0;
        do {
            var t = trang(toi, "?limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(t.ids()).hasSizeLessThanOrEqualTo(3);
            daDi.addAll(t.ids());
            cursor = t.nextCursor();
            soTrang++;
        } while (cursor != null && soTrang < 10);
        assertThat(daDi).as("đi hết trang: đúng 7 bài, mới nhất trước, không trùng, không sót")
                .containsExactly(b7, b6, b5, b4, b3, b2, b1);
    }

    private record Trang(List<Long> ids, String nextCursor) {
    }

    @SuppressWarnings("unchecked")
    private Trang trang(String token, String query) {
        var res = goi(http.get().uri(NOP + query).header(HttpHeaders.AUTHORIZATION, token));
        assertThat(res.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        var ids = ((List<Map<String, Object>>) res.getBody().get("items")).stream()
                .map(bai -> ((Number) bai.get("submissionId")).longValue())
                .toList();
        return new Trang(ids, (String) res.getBody().get("nextCursor"));
    }

    private long baiDaCham(long problemId, String ngonNgu, String verdict) {
        return jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                         source_bytes, testdata_version, status, attempt,
                                         verdict, score, max_score, judged_at)
                SELECT :u, :p, l.id, :sha, 12, 1, 'DONE', 1, :v,
                       CASE WHEN :v = 'AC' THEN 100 ELSE 0 END, 100, now()
                  FROM languages l
                 WHERE l.code = :lang
                RETURNING id
                """).param("u", USER_ID).param("p", problemId).param("sha", "a".repeat(64))
                .param("v", verdict).param("lang", ngonNgu)
                .query(Long.class).single();
    }

    private RestClient.RequestHeadersSpec<?> nop(String token) {
        return http.post().uri(NOP)
                .header(HttpHeaders.AUTHORIZATION, token)
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                        "source", "// EXPECT: AC\nint main(){}"));
    }

    private static long idBai(ResponseEntity<Map<String, Object>> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return ((Number) res.getBody().get("submissionId")).longValue();
    }

    private ResponseEntity<Map<String, Object>> xem(long id, String token) {
        return goi(http.get().uri(NOP + "/{id}", id).header(HttpHeaders.AUTHORIZATION, token));
    }

    @SuppressWarnings("unchecked")
    private List<Long> lichSu(String token) {
        var res = goi(http.get().uri(NOP).header(HttpHeaders.AUTHORIZATION, token));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) res.getBody().get("items")).stream()
                .map(bai -> ((Number) bai.get("submissionId")).longValue())
                .toList();
    }

    private int soBaiNop() {
        return jdbc.sql("""
                SELECT count(*)::int FROM submissions
                """).query(Integer.class).single();
    }
}
