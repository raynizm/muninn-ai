package se.tcmt.muninn.support;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TestData {

  public static byte[] bytes(int n, long seed) {
    byte[] b = new byte[n];
    new Random(seed).nextBytes(b);
    return b;
  }

  public static String sha(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }
}
