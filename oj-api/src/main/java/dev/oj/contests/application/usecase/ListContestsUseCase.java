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

        public static TrangThai cua(Contest c, Instant bayGio) {
            if (bayGio.isBefore(c.startsAt())) {
                return SAP_DIEN_RA;
            }
            return bayGio.isBefore(c.endsAt()) ? DANG_CHAY : DA_KET_THUC;
        }
    }
}
