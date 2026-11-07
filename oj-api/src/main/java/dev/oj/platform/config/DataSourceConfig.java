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

@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("oj.datasource.app")
    public HikariConfig appHikariConfig() {
        return new HikariConfig();
    }

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

    @Bean
    @Primary
    public PlatformTransactionManager appTransactionManager(
            @Qualifier("appDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public PlatformTransactionManager judgeTransactionManager(
            @Qualifier("judgeDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
