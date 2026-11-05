package com.theodomart.energy.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

	@Test
	void findsTheConstraintNameDeepInTheCauseChain() {
		var hibernate = new ConstraintViolationException("duplicate", new SQLException("duplicate"), "uk_something");
		var spring = new DataIntegrityViolationException("could not execute statement", hibernate);

		assertThat(ConstraintViolations.constraintName(spring)).isEqualTo("uk_something");
	}

	@Test
	void returnsNullWhenThereIsNoConstraintViolation() {
		assertThat(ConstraintViolations.constraintName(new DataIntegrityViolationException("other"))).isNull();
		assertThat(ConstraintViolations.constraintName(null)).isNull();
	}

}
