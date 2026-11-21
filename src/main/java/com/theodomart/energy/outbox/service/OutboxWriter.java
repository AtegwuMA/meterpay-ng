package com.theodomart.energy.outbox.service;

public interface OutboxWriter {

	/**
	 * Records the event in the caller's transaction, so it is published exactly when that transaction commits and
	 * never when it rolls back. The correlation ID is copied from the MDC, or generated when there is none.
	 *
	 * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active: an
	 * event written on its own could describe a change that never happened
	 */
	void write(DomainEvent event);

}
