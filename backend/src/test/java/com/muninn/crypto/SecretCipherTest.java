package com.muninn.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher(new byte[32]);

    @Test
    void roundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt("sk-abc"))).isEqualTo("sk-abc");
    }

    @Test
    void randomNonce() {
        assertThat(cipher.encrypt("sk-abc")).isNotEqualTo(cipher.encrypt("sk-abc"));
    }

    @Test
    void tamperFails() {
        String ciphertext = cipher.encrypt("sk-abc");
        char first = ciphertext.charAt(0);
        char flipped = first == 'A' ? 'B' : 'A';
        String tampered = flipped + ciphertext.substring(1);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ciphertextHasNoPlaintext() {
        String ciphertext = cipher.encrypt("sk-abc");
        assertThat(ciphertext).doesNotContain("sk-abc");
    }
}
