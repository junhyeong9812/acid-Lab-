package com.experiment.acidlab.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.experiment.acidlab.global.logger.ConsoleLogger;
import com.experiment.acidlab.global.metrics.MetricsCollector;

/**
 * Spring Bean 설정
 */
@Configuration
public class BeanConfig {

    @Bean
    public MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    public ConsoleLogger consoleLogger() {
        return new ConsoleLogger("ACID-Lab");
    }
}