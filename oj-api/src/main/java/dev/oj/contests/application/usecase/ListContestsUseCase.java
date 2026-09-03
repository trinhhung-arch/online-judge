package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.PublicAccess;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Danh sách kỳ thi — FR-CON-01, Bước G4.
 *
 * <h2>★ KHÔNG trả về danh sách đề, và đó là toàn bộ lý do lớp này tồn tại riêng</h2>
 * {@link GetContestUseCase} đã có luật: đề chỉ hiện khi kỳ thi đã bắt đầu, vì <i>chính danh
 * sách mã đề</i> đã là thông tin. Nhưng nó áp luật ấy cho <b>một</b> kỳ thi.
 *
 * <p>Tái dùng nó cho trang danh sách là mời một lỗi rất cụ thể: một vòng lặp gọi
 * {@code theoSlug} cho mười kỳ thi trông vô hại, nhưng nó là mười lần đọc bảng
 * {@code contest_problems} — và nếu ai đó bỏ nhánh {@code hienDe} khi ghép DTO thì đề của kỳ
 * thi chưa mở lọt ra qua đúng cái endpoint dùng để <i>tìm</i> kỳ thi. Ở đây không có gì để
 * lọt: lớp này không bao giờ đọc đề.
 *
 * <h2>Không lọc theo người gọi</h2>
 * Lịch thi là công khai — người ta phải xem được để quyết định có đăng ký không, kể cả khi
 * chưa có tài khoản. {@code daDangKy} <b>không</b> có ở đây: nó cần một truy vấn cho mỗi
 * dòng, và trang danh sách không cần nó. Bấm vào một kỳ thi thì {@code GetContestUseCase}
 * trả lời.
 */
@PublicAccess("Lịch thi là thông tin công khai. Danh sách đề của từng kỳ thi thì không, và "
        + "lớp này không đọc chúng.")
@Service
public class ListContestsUseCase {

    private final ContestRepository contests;
    private final AppProperties properties;
    private final Clock clock;

    public ListContestsUseCase(ContestRepository contests, AppProperties properties,
                               Clock clock) {
        this.contests = contests;
        this.properties = properties;
        this.clock = clock;
    }

    public CursorPage<TomTat> thucHien(String cursor, Integer size) {
        int gioiHan = CursorPage.clampSize(size,
                properties.page().defaultSize(), properties.page().maxSize());

        Long sauId = docCursor(cursor);
        Instant bayGio = clock.instant();

        List<TomTat> dong = contests.danhSach(sauId, gioiHan + 1).stream()
                .map(c -> TomTat.tu(c, bayGio))
                .toList();

        return CursorPage.of(dong, gioiHan, t -> String.valueOf(t.id()));
    }

    /**
     * Cursor rác trả về trang đầu, <b>không</b> ném lỗi.
     *
     * <p>Cursor là chi tiết nội bộ mà client chỉ chép lại từ response trước. Một chuỗi hỏng
     * nghĩa là link bị cắt hoặc ai đó gõ tay — trả trang đầu là hành vi mà người dùng hiểu
     * được, còn một lỗi 400 nói về "cursor" thì không.
     */
    private static Long docCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Một dòng của trang danh sách. <b>Không có danh sách đề</b> — xem javadoc của class.
     *
     * @param trangThai suy ra ở server, không phải ở client. Trình duyệt có đồng hồ riêng và
     *                  nó lệch; một kỳ thi hiện "đang chạy" trên máy thí sinh trong khi server
     *                  nói chưa mở là một khiếu nại không ai giải quyết được
     */
    public record TomTat(long id, String slug, String title, String format,
                         Instant startsAt, Instant endsAt,
                         boolean registrationRequired, TrangThai trangThai) {

        static TomTat tu(Contest c, Instant bayGio) {
            return new TomTat(c.id(), c.slug(), c.title(), c.format().code(),
                    c.startsAt(), c.endsAt(), c.registrationRequired(),
                    TrangThai.cua(c, bayGio));
        }
    }

    public enum TrangThai {
        SAP_DIEN_RA, DANG_CHAY, DA_KET_THUC;

        /** Public vì {@code ContestResponses} ở package khác cũng cần đúng phép suy này. */
        public static TrangThai cua(Contest c, Instant bayGio) {
            if (bayGio.isBefore(c.startsAt())) {
                return SAP_DIEN_RA;
            }
            return bayGio.isBefore(c.endsAt()) ? DANG_CHAY : DA_KET_THUC;
        }
    }
}
