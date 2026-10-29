package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class MySqlTestContainersConfig {

    private static final MySQLContainer<?> mySqlContainer;

    static {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("loopers")
            .withUsername("test")
            .withPassword("test")
            .withExposedPorts(3306)
            .withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_general_ci",
                "--skip-character-set-client-handshake"
            );
        // 로컬 compose 로 앱 사이를 잇는 실험에서만 켬. test profile 의 localhost 주소를 그대로 씀 (6주차 설계 7.2)
        if (!"true".equals(System.getenv("TEST_LOCAL_INFRA"))) {
            mySqlContainer.start();

            String mySqlJdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s",
                mySqlContainer.getHost(),
                mySqlContainer.getFirstMappedPort(),
                mySqlContainer.getDatabaseName()
            );

            System.setProperty("datasource.mysql-jpa.main.jdbc-url", mySqlJdbcUrl);
            System.setProperty("datasource.mysql-jpa.main.username", mySqlContainer.getUsername());
            System.setProperty("datasource.mysql-jpa.main.password", mySqlContainer.getPassword());
        }
    }
}
