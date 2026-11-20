package se.tcmt.muninn.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import se.tcmt.muninn.service.JobWorker;

/**
 * Polls for processing jobs every 2s on Spring's scheduler (one thread by default; fixed delay, so
 * polls never overlap) and drains whatever is due. Off when {@code muninn.worker.enabled=false},
 * e.g. in tests that call {@link JobWorker#runOnce()} themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "muninn.worker.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
class JobScheduling {

  private final JobWorker worker;

  /** Runs due processing jobs back to back until none are left. */
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
