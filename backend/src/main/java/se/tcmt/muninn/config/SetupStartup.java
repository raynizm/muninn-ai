package se.tcmt.muninn.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import se.tcmt.muninn.service.SetupService;

@Component
@RequiredArgsConstructor
class SetupStartup implements ApplicationRunner {

  private final SetupService setup;

  @Value("${muninn.reissue-setup-token:false}")
  private final boolean reissue;

  /** Issues (or reissues) the setup or recovery token at startup when needed. */
  @Override
  public void run(ApplicationArguments args) {
    if (reissue) {
      setup.reissueToken();
    } else {
      setup.issueTokenIfNeeded();
    }
  }
}
