package dev.oj.platform.config;

import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @Transactional} của <b>đường verdict</b> — gắn vào {@code judgeTransactionManager},
 * tức là pool 6 connection dành riêng cho {@code /internal/judge/*}.
 *
 * <h2>Vì sao cần một annotation riêng thay vì nhớ gõ đúng tham số</h2>
 * Hệ thống có hai {@code DataSource}. Một use-case ghi qua {@code judgeJdbcClient} nhưng chạy
 * dưới {@code appTransactionManager} sẽ <b>không được bao trong transaction nào cả</b> —
 * mỗi câu lệnh tự commit. Với {@link dev.oj.platform.config.DataSourceConfig} đã giải thích:
 * đó là lúc khoá lạc quan, {@code INSERT judge_runs} và {@code UPDATE submissions} rời nhau
 * ra, và R2 ("0 bài bị chấm 2 lần") vỡ mà không có lỗi nào được ném.
 *
 * <p>Sai lầm đó là <b>một chuỗi bị gõ thiếu</b>, không phải một quyết định. Nên nó được biến
 * thành một cái tên: hoặc bạn viết {@code @JudgeTransactional}, hoặc bạn không ở trên đường
 * verdict. Không có trạng thái thứ ba.
 *
 * <h2>Chỉ dùng ở đúng ba chỗ</h2>
 * <ul>
 *   <li>{@code ClaimJudgeJobUseCase} — claim + đánh dấu {@code JUDGING}</li>
 *   <li>{@code RecordJudgeResultUseCase} — khoá lạc quan + {@code judge_runs} + {@code submissions}</li>
 *   <li>{@code ReapStaleJobsUseCase} — thu hồi lease hết hạn</li>
 * </ul>
 * Thấy nó ở chỗ thứ tư thì dừng lại và hỏi: hoặc bạn vừa thêm một chặng vào đường chấm bài
 * (ngân sách 2 giây, {@code CLAUDE.md} mục 4.6), hoặc bạn đang dùng nhầm pool.
 *
 * <p><b>Không dùng cho {@code SubmitSolutionUseCase}.</b> Nộp bài là request của người dùng,
 * nó thuộc pool {@code app} và dùng {@code @Transactional} thường.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Transactional("judgeTransactionManager")
public @interface JudgeTransactional {
}
