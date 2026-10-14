package com.loopers.experiment;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 상품 조회 측정 실험 (4주차). 제품 코드가 아니며 결과는 설계 문서와 기술 글의 근거로 씀.
 *
 * <ul>
 *   <li>측정용 데이터는 seed/week4-product-seed.sql 로 넣음. 규칙과 검증값은 docs/week4/product-query-seed.md</li>
 *   <li>seed 가 어긋나면 측정 전에 실패하도록 건수 · 합계를 먼저 확인함</li>
 * </ul>
 * 실행: RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*ProductQueryExperiment'
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
class ProductQueryExperiment {

    private static final String SEED = "seed/week4-product-seed.sql";
    private static final long MEASURED_BRAND_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seed() throws SQLException {
        jdbcTemplate = new JdbcTemplate(dataSource);
        databaseCleanUp.truncateAllTables();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED));
        }
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void seedMatchesExpectedValues() {
        assertAll(
            () -> assertThat(count("select count(*) from users")).isEqualTo(1_000),
            () -> assertThat(count("select count(*) from brand where deleted_at is null")).isEqualTo(100),
            () -> assertThat(count("select count(*) from product")).isEqualTo(10_000),
            () -> assertThat(count("select sum(price) from product")).isEqualTo(472_685_000),
            () -> assertThat(count("select count(*) from product where deleted_at is null")).isEqualTo(9_412),
            () -> assertThat(count("select sum(price) from product where deleted_at is null")).isEqualTo(445_004_786),
            () -> assertThat(count("select count(*) from product where brand_id = ?", MEASURED_BRAND_ID)).isEqualTo(100),
            () -> assertThat(count("select sum(price) from product where brand_id = ?", MEASURED_BRAND_ID)).isEqualTo(4_647_200),
            () -> assertThat(count("select count(*) from product where brand_id = ? and deleted_at is null", MEASURED_BRAND_ID))
                .isEqualTo(94),
            () -> assertThat(count("select sum(price) from product where brand_id = ? and deleted_at is null", MEASURED_BRAND_ID))
                .isEqualTo(4_435_168),
            () -> assertThat(count("select count(*) from likes")).isEqualTo(100_163),
            () -> assertThat(count("select count(*) from likes l join product p on p.id = l.product_id where p.deleted_at is null"))
                .isEqualTo(94_276),
            () -> assertThat(count("select count(*) from likes l left join users u on u.id = l.user_id where u.id is null"))
                .isZero()
        );
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
