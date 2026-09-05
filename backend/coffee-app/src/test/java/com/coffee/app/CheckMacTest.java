package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.payments.api.CheckMac;
import java.util.*;
import org.junit.jupiter.api.Test;

class CheckMacTest {
  @Test
  void matchesOfficialEcpayReferenceVector() {
    var p = new HashMap<String, String>();
    String input =
        "TradeDesc=促銷方案&PaymentType=aio&MerchantTradeDate=2023/03/12"
            + " 15:30:23&MerchantTradeNo=ecpay20230312153023&MerchantID=3002607&ReturnURL=https://www.ecpay.com.tw/receive.php&ItemName=Apple"
            + " iphone 15&TotalAmount=30000&ChoosePayment=ALL&EncryptType=1";
    for (String pair : input.split("&")) {
      var kv = pair.split("=", 2);
      p.put(kv[0], kv[1]);
    }
    assertThat(CheckMac.sign(p, "pwFHCqoQZGmho4w6", "EkRm7iFT261dpevs"))
        .isEqualTo("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840");
  }
}
