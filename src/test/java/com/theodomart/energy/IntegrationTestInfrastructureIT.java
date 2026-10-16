package com.theodomart.energy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class IntegrationTestInfrastructureIT extends IntegrationTest {

	private final JdbcTemplate jdbc;

	private final StringRedisTemplate redis;

	private final KafkaTemplate<String, String> kafka;

	@Autowired
	IntegrationTestInfrastructureIT(JdbcTemplate jdbc, StringRedisTemplate redis, KafkaTemplate<String, String> kafka) {
		this.jdbc = jdbc;
		this.redis = redis;
		this.kafka = kafka;
	}

	@Test
	void postgresIsReachableAndMigrated() {
		String version = jdbc.queryForObject("SHOW server_version", String.class);
		Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);

		assertThat(version).startsWith("16.");
		assertThat(applied).isPositive();
	}

	@Test
	void redisStoresAndReturnsAValue() {
		String key = "it:" + UUID.randomUUID();

		redis.opsForValue().set(key, "ok");

		assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
	}

	@Test
	void kafkaAcknowledgesAPublishedRecord() throws Exception {
		SendResult<String, String> result = kafka.send("it.smoke", "key", "value").get(30, TimeUnit.SECONDS);

		assertThat(result.getRecordMetadata().topic()).isEqualTo("it.smoke");
		assertThat(result.getRecordMetadata().offset()).isGreaterThanOrEqualTo(0);
	}

	@Test
	void allIntegrationTestsShareOneSetOfContainers() {
		assertThat(POSTGRES.isRunning()).isTrue();
		assertThat(REDIS.isRunning()).isTrue();
		assertThat(KAFKA.isRunning()).isTrue();
	}

}
