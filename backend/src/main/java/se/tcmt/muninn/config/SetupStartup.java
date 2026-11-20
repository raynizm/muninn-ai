package se.tcmt.muninn.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import se.tcmt.muninn.service.SetupService;

@Component
class SetupStartup implements ApplicationRunner {

  private final SetupService setup;
  private final boolean reissue;

  /** Creates the runner; {@code reissue} forces a new setup token. */
  SetupStartup(SetupService setup, @Value("${muninn.reissue-setup-token:false}") boolean reissue) {
    this.setup = setup;
    this.reissue = reissue;
  }

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
