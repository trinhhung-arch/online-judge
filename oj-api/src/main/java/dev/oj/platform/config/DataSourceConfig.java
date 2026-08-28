package dev.oj.platform.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Hai connection pool, cố ý tách nhau — {@code postgres-design.md} mục 11.
 *
 * <h2>Vì sao không dùng chung một pool</h2>
 * 500 người nộp bài cùng lúc có thể vét sạch pool. Nếu worker dùng chung pool đó, nó
 * <b>không lấy được connection để ghi verdict</b>. Bài đang chấm dở bị reaper thu hồi sau 120s,
 * chấm lại, lại không ghi được — và mỗi vòng lại tiêu thêm một judge slot. Hệ thống không sập,
 * nó chỉ chậm dần cho tới khi không còn chấm được gì.
 *
 * <p>Hai pool tách nhau thì <b>đường verdict không bao giờ bị đói vì đường đọc</b>. Số 6 khớp
 * với số judge slot ({@code nfrplan.md} 2.2), nên mỗi worker đang chấm luôn có sẵn đúng một
 * connection để trả kết quả.
 *
 * <pre>
 *   app   (20)  ← mọi request người dùng: /api/v1/**
 *   judge  (6)  ← CHỈ /internal/judge/claim và /internal/judge/result
 * </pre>
 *
 * <h2>⚠️ Bẫy: hai DataSource thì phải có hai TransactionManager</h2>
 * {@code @Transactional} không tự biết bạn đang ghi qua pool nào. Nếu một use-case chạy dưới
 * {@code appTransactionManager} nhưng phát câu lệnh qua {@code judgeJdbcClient}, thì
 * <b>transaction đó không bao lấy các câu lệnh ấy</b> — chúng chạy autocommit. Với
 * {@code RecordJudgeResultUseCase} điều đó nghĩa là khoá lạc quan, {@code INSERT judge_runs}
 * và {@code UPDATE submissions} không còn nguyên tử: R2 ("0 bài chấm 2 lần") vỡ, và vỡ im lặng.
 *
 * <p>Nên đường verdict <b>bắt buộc</b> dùng {@link JudgeTransactional @JudgeTransactional},
 * không phải {@code @Transactional} trần.
 *
 * <h2>Flyway chạy bằng role thứ ba</h2>
 * Hai pool ở đây đều là {@code oj_app} — role bị {@code REVOKE DELETE, TRUNCATE ON submissions}
 * và {@code REVOKE UPDATE, DELETE ON audit_log} (V9). Flyway cần DDL, nên nó dùng
 * {@code oj_migrator} qua {@code spring.flyway.user/password} — cấu hình riêng, không đi qua
 * class này. Đó chính là điều làm cho "audit_log append-only" là một quyền hệ thống chứ không
 * phải một quy ước ({@code postgres-design.md} mục 9).
 */
@Configuration
public class DataSourceConfig {

    /** Cấu hình pool cho request người dùng. Bind từ {@code oj.datasource.app.*}. */
    @Bean
    @Primary
    @ConfigurationProperties("oj.datasource.app")
    public HikariConfig appHikariConfig() {
        return new HikariConfig();
    }

    /** Cấu hình pool cho {@code /internal/judge/*}. Bind từ {@code oj.datasource.judge.*}. */
    @Bean
    @ConfigurationProperties("oj.datasource.judge")
    public HikariConfig judgeHikariConfig() {
        return new HikariConfig();
    }

    @Bean
    @Primary
    public DataSource appDataSource(@Qualifier("appHikariConfig") HikariConfig config) {
        config.setPoolName("oj-app");
        return new HikariDataSource(config);
    }

    @Bean
    public DataSource judgeDataSource(@Qualifier("judgeHikariConfig") HikariConfig config) {
        config.setPoolName("oj-judge");
        return new HikariDataSource(config);
    }

    /**
     * Transaction manager mặc định — mọi thứ trừ đường verdict.
     *
     * <p>{@code @Primary} nên {@code @Transactional} trần sẽ dùng cái này. Đó là ý đồ:
     * quên chú thích thì bạn rơi vào pool người dùng, chậm chứ không sai. Nhầm chiều ngược lại
     * (đường verdict rơi vào pool app) mới là cái phá R2, và {@code @JudgeTransactional} tồn tại
     * để chuyện đó không xảy ra vì quên.
     */
    @Bean
    @Primary
    public PlatformTransactionManager appTransactionManager(
            @Qualifier("appDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    /** Transaction manager của đường verdict. Tên bean này được {@code @JudgeTransactional} tham chiếu. */
    @Bean
    public PlatformTransactionManager judgeTransactionManager(
            @Qualifier("judgeDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
