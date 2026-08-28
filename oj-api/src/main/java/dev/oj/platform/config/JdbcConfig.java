package dev.oj.platform.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Một {@link JdbcClient} cho mỗi pool.
 *
 * <h2>JdbcClient, không phải JdbcTemplate, không phải JPA</h2>
 * <ul>
 *   <li><b>Không JPA/Hibernate.</b> Đường nộp bài có ngân sách 50ms cho phần DB, và các truy vấn
 *       nóng ở {@code docs/sql/duong_nong.sql} dùng {@code FOR UPDATE SKIP LOCKED},
 *       {@code DELETE ... RETURNING}, {@code ON CONFLICT ... WHERE} — những thứ mà một ORM
 *       hoặc không diễn đạt được, hoặc diễn đạt xong thì không ai đọc ra.</li>
 *   <li><b>Không {@code JdbcTemplate}.</b> Nó nhận {@code Object...} theo <i>vị trí</i>, nên
 *       nối chuỗi vào SQL trở nên tiện tay. {@code JdbcClient} có named parameter, và luật
 *       {@code luat5c_chi_jdbcclient} trong {@code ArchitectureTest} chặn {@code JdbcTemplate}
 *       ở CI (bất biến #5).</li>
 * </ul>
 *
 * <h2>Chép SQL, đừng viết lại</h2>
 * Mười hai truy vấn trong {@code docs/sql/duong_nong.sql} đã được đo và kiểm chứng trên
 * PostgreSQL 16 ở 1.000.000 dòng. Viết lại "cho gọn" là cách nhanh nhất để mất
 * {@code SKIP LOCKED}, mất {@code RETURNING}, hoặc vô tình thêm một {@code COUNT(*)}.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class JdbcConfig {

    /** Dùng bởi mọi repository phục vụ request người dùng. */
    @Bean
    @Primary
    public JdbcClient appJdbcClient(@Qualifier("appDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /**
     * Dùng bởi đúng ba repository của đường verdict: {@code JdbcJudgeQueueRepository},
     * {@code JdbcJudgeRunRepository}, và phần {@code markJudging}/{@code markDone} của
     * {@code JdbcSubmissionRepository}.
     *
     * <p>Mọi use-case dùng client này phải mang {@link JudgeTransactional} — xem javadoc
     * của annotation đó để biết chuyện gì xảy ra nếu quên.
     */
    @Bean
    public JdbcClient judgeJdbcClient(@Qualifier("judgeDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
