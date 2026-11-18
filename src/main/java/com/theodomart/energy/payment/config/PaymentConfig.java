package com.theodomart.energy.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ PaystackProperties.class, ReconciliationProperties.class })
class PaymentConfig {

}
