package com.theodomart.energy.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled for every module, so no module switches it on for the others as a side effect. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

}
