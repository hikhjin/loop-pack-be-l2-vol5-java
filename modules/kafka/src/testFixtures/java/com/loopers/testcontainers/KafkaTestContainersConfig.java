package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 로컬 compose 와 같은 Kafka 3.5 대 broker (cp-kafka 7.5 = Apache Kafka 3.5, 6주차 D-97) */
@Configuration
public class KafkaTestContainersConfig {

    private static final ConfluentKafkaContainer kafkaContainer;

    // 생성자에서 넣으면 Kafka 설정이 먼저 만들어질 때 기본 주소로 묶이므로 클래스 초기화 때 넣음 (MySqlTestContainersConfig 와 같음)
    static {
        kafkaContainer = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.3"))
            // topic 은 테스트가 정한 partition 수로 만듦 (6주차 D-95)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        kafkaContainer.start();

        System.setProperty("spring.kafka.bootstrap-servers", kafkaContainer.getBootstrapServers());
    }
}
