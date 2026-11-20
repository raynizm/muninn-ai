package com.muninn.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls for processing jobs every 2s on Spring's scheduler (one thread by default; fixed delay, so polls never
 * overlap) and drains whatever is due. Off when {@code muninn.worker.enabled=false}, e.g. in tests that call
 * {@link JobWorker#runOnce()} themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "muninn.worker.enabled", havingValue = "true", matchIfMissing = true)
class JobScheduling {

    private static final Logger log = LoggerFactory.getLogger(JobScheduling.class);

    private final JobWorker worker;

    JobScheduling(JobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelay = 2000, fixedDelay = 2000)
    void poll() {
        try {
            while (worker.runOnce()) {
                // keep going while jobs are due
            }
        } catch (RuntimeException e) {
            log.warn("Job poll failed: {}", e.toString());
        }
    }
}
