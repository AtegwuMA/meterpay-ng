package com.theodomart.energy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class MeterPayApplicationIT extends IntegrationTest {

	private final ApplicationContext context;

	@Autowired
	MeterPayApplicationIT(ApplicationContext context) {
		this.context = context;
	}

	@Test
	void contextLoads() {
		assertThat(context).isNotNull();
	}

}
