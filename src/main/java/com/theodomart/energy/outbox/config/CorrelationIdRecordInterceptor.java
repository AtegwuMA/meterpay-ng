package com.theodomart.energy.outbox.config;

import com.theodomart.energy.common.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Puts a message's {@code X-Correlation-Id} into the MDC while a listener handles it, and clears it afterwards
 * (DESIGN 8.4). The Kafka counterpart of {@link CorrelationIdFilter}, with the same rule for unsafe values: a
 * header is as untrusted as a request header. Boot applies it to every {@code @KafkaListener}.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

	@Override
	public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
			Consumer<Object, Object> consumer) {
		Header header = record.headers().lastHeader(CorrelationIdFilter.HEADER);
		String incoming = header == null || header.value() == null ? null
				: new String(header.value(), StandardCharsets.UTF_8);
		MDC.put(CorrelationIdFilter.MDC_KEY, CorrelationIdFilter.safeOrNew(incoming));
		return record;
	}

	// Listener threads are reused: without this the next message would inherit the ID.
	@Override
	public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
		MDC.remove(CorrelationIdFilter.MDC_KEY);
	}

}
