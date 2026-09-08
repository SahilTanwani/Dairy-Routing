package com.dairy.milkroute.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduled sweeps.
 *
 * <p>A configuration class rather than an annotation on the application class, so that a
 * test slice which does not want a background monitor running underneath it can leave this
 * one out.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
