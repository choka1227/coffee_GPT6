package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import com.coffee.payments.internal.EinvoiceCodec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EinvoiceCodecTest {
  private static final String KEY = "0123456789abcdef", IV = "fedcba9876543210";

  @ParameterizedTest
  @ValueSource(strings = {"中文 餐飲費用", " ~*+/=", "{\"x\":\"+%/~\"}", "", "😀"})
  void roundTrip(String source) {
    String encrypted = EinvoiceCodec.encrypt(source, KEY, IV);
    assertThat(encrypted).doesNotContain("\n");
    assertThat(EinvoiceCodec.decrypt(encrypted, KEY, IV)).isEqualTo(source);
  }

  @Test
  void encodingUsesPhpRulesAndAesEncryptsTheEncodedText() throws Exception {
    assertThat(EinvoiceCodec.phpUrlEncode(" ~*")).isEqualTo("+%7E%2A");
    assertThat(EinvoiceCodec.phpUrlDecode("%E4%B8%AD%E6%96%87+%2B")).isEqualTo("中文 +");
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"),
        new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
    assertThat(new String(cipher.doFinal(Base64.getDecoder().decode(
        EinvoiceCodec.encrypt(" ~*", KEY, IV))), StandardCharsets.UTF_8)).isEqualTo("+%7E%2A");
  }

  @Test
  void failuresNeverCarrySecretsOrInputInMessageOrCause() {
    String cipher = EinvoiceCodec.encrypt("private@example.test", KEY, IV);
    for (Runnable attempt : new Runnable[] {
        () -> EinvoiceCodec.decrypt(cipher, "bad-key", IV),
        () -> EinvoiceCodec.decrypt("bad-cipher", KEY, IV),
        () -> EinvoiceCodec.phpUrlDecode("%ZZ")}) {
      assertThatThrownBy(attempt::run).isInstanceOf(RuntimeException.class)
          .hasMessage("電子發票資料編解碼失敗").hasNoCause();
    }
  }
}
