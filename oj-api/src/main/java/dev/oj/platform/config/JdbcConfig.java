package dev.oj.platform.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class JdbcConfig {

    @Bean
    @Primary
    public JdbcClient appJdbcClient(@Qualifier("appDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    public JdbcClient judgeJdbcClient(@Qualifier("judgeDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
