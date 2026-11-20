package com.muninn.setup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class SetupStartup implements ApplicationRunner {

    private final SetupService setup;
    private final boolean reissue;

    SetupStartup(SetupService setup, @Value("${muninn.reissue-setup-token:false}") boolean reissue) {
        this.setup = setup;
        this.reissue = reissue;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (reissue) {
            setup.reissueToken();
        } else {
            setup.issueTokenIfNeeded();
        }
    }
}
