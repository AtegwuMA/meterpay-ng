package com.theodomart.energy.outbox.config;

import com.theodomart.energy.outbox.service.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.RecordInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfig {

	/** Created at startup if missing. Partitions let consumers scale; the key keeps one aggregate on one. */
	@Bean
	NewTopic energyEventsTopic(OutboxProperties properties) {
		return TopicBuilder.name(EventTopics.ENERGY_EVENTS).partitions(properties.topicPartitions()).build();
	}

	/** Boot hands a single RecordInterceptor bean to its listener container factory. */
	@Bean
	RecordInterceptor<Object, Object> correlationIdRecordInterceptor() {
		return new CorrelationIdRecordInterceptor();
	}

}
