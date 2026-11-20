package se.tcmt.muninn.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for secrets at rest (AI provider API keys, OIDC client secret). Output is
 * Base64 of {@code nonce(12) || ciphertext+tag(16)}.
 */
public final class SecretCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /** Creates a cipher from a 32-byte master key. */
  public SecretCipher(byte[] masterKey32) {
    if (masterKey32 == null || masterKey32.length != KEY_BYTES) {
      throw new IllegalArgumentException("master key must be exactly " + KEY_BYTES + " bytes");
    }
    this.key = new SecretKeySpec(masterKey32.clone(), "AES");
  }

  /** Encrypts the text with a fresh IV and returns it as a base64 string. */
  public String encrypt(String plaintext) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out =
          ByteBuffer.allocate(NONCE_BYTES + ciphertext.length).put(nonce).put(ciphertext).array();
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  /**
   * Decrypts a value produced by {@link #encrypt}; fails when it is malformed or was tampered with.
   */
  public String decrypt(String ciphertext) {
    byte[] in = Base64.getDecoder().decode(ciphertext);
    if (in.length < NONCE_BYTES + TAG_BITS / 8) {
      throw new IllegalArgumentException("malformed ciphertext");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
      byte[] plaintext = cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("ciphertext authentication failed", e);
    }
  }
}
