package com.acme.billing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BillingDataSourceConfig {

    private static final String URL = "jdbc:postgresql://billing-db.internal:5432/billing";
    private static final String USERNAME = "billing_app";
    private static final String PASSWORD = "Pr0d-B1lling-2026!";

    @Bean
    public DataSource billingDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        config.setMaximumPoolSize(10);
        config.setPoolName("billing");
        return new HikariDataSource(config);
    }
}
