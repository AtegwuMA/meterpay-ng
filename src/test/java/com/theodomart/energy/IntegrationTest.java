package com.theodomart.energy;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for every *IT. Images match docker-compose.yml so tests run against the same
 * versions as local development.
 */
@SpringBootTest
public abstract class IntegrationTest {

	// Singleton containers: started once per JVM, not per class. @Container would stop them after
	// each class and also break Spring's context cache, which keys on the connection details.
	// Ryuk removes them when the JVM exits.
	@ServiceConnection
	protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

	@ServiceConnection(name = "redis")
	protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@ServiceConnection
	protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

	static {
		POSTGRES.start();
		REDIS.start();
		KAFKA.start();
	}

}
