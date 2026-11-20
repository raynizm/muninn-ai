package se.tcmt.muninn.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.tcmt.muninn.crypto.SecretCipher;

@Configuration
public class CryptoConfig {

    private static final int KEY_BYTES = 32;

    /**
     * Loads the master key from the Docker secret file named by MUNINN_MASTER_KEY_FILE.
     * The file must contain exactly 32 raw bytes; startup fails otherwise.
     * The key bytes are never logged or included in exception messages.
     */
    @Bean
    SecretCipher secretCipher(@Value("${MUNINN_MASTER_KEY_FILE:}") String keyFile) {
        if (keyFile == null || keyFile.isBlank()) {
            throw new IllegalStateException("MUNINN_MASTER_KEY_FILE is not set");
        }
        byte[] key;
        try {
            key = Files.readAllBytes(Path.of(keyFile));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read master key file " + keyFile, e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException("master key file must be exactly " + KEY_BYTES + " bytes");
        }
        return new SecretCipher(key);
    }
}
