package com.coffee.payments.internal;

import com.coffee.orders.api.Orders;
import com.coffee.payments.api.CheckMac;
import com.coffee.shared.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EcpayService {
  private final Orders orders;
  private final JdbcTemplate db;
  private final boolean enabled;
  private final String merchant, key, iv, base, environment;

  public EcpayService(
      Orders orders,
      JdbcTemplate db,
      @Value("${ecpay.enabled:false}") boolean enabled,
      @Value("${ecpay.merchant-id:}") String merchant,
      @Value("${ecpay.hash-key:}") String key,
      @Value("${ecpay.hash-iv:}") String iv,
      @Value("${app.public-url:http://localhost:8080}") String base,
      @Value("${ecpay.environment:stage}") String environment) {
    this.orders = orders;
    this.db = db;
    this.enabled = enabled;
    this.merchant = merchant;
    this.key = key;
    this.iv = iv;
    this.base = base.replaceAll("/+$", "");
    this.environment = environment;
    if (enabled) {
      Problem.check(Set.of("stage", "production").contains(environment), "金流環境設定錯誤");
      Problem.check(!merchant.isBlank() && !key.isBlank() && !iv.isBlank(), "啟用綠界時必須設定商店憑證");
      Problem.check(base.startsWith("https://"), "綠界回呼需可公開連線的 HTTPS 網域");
      if (environment.equals("production"))
        Problem.check(!Set.of("3002607", "3002599", "3365120").contains(merchant), "正式環境不能使用測試商店");
    }
  }

  public Map<String, Object> config() {
    return Map.of("enabled", enabled, "environment", environment);
  }

  @Transactional
  public Map<String, Object> checkout(Actor a, String id) {
    if (!enabled) throw new Problem(503, "此門市尚未啟用線上付款，請改選櫃台付款");
    Orders.Order o = orders.payable(a, id);
    Map<String, String> p = new TreeMap<>();
    p.put("MerchantID", merchant);
    p.put("MerchantTradeNo", o.id());
    p.put(
        "MerchantTradeDate",
        Instant.ofEpochMilli(o.createdAt())
            .atZone(ZoneId.of("Asia/Taipei"))
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")));
    p.put("PaymentType", "aio");
    p.put("TotalAmount", Integer.toString(o.total()));
    p.put("TradeDesc", "Coffee order");
    p.put("ItemName", "MORNING POUR 咖啡餐點");
    p.put("ReturnURL", base + "/api/payments/ecpay/callback");
    p.put("ClientBackURL", base + "/orders?order=" + o.id());
    p.put("ChoosePayment", "Credit");
    p.put("EncryptType", "1");
    p.put("CheckMacValue", CheckMac.sign(p, key, iv));
    return Map.of(
        "action",
        environment.equals("production")
            ? "https://payment.ecpay.com.tw/Cashier/AioCheckOut/V5"
            : "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
        "fields",
        p);
  }

  @Transactional
  public String callback(Map<String, String> p) {
    if (!enabled || !CheckMac.valid(p, key, iv) || !merchant.equals(p.get("MerchantID")))
      throw new Problem(400, "付款通知驗證失敗");
    String id = p.get("MerchantTradeNo");
    var order = orders.paymentSnapshot(id);
    int amount;
    try {
      amount = Integer.parseInt(p.get("TradeAmt"));
    } catch (Exception ex) {
      throw new Problem(400, "付款金額格式錯誤");
    }
    Problem.check(order.total() == amount && order.paymentMethod().equals("ECPAY"), "付款資訊不符");
    String trade = p.getOrDefault("TradeNo", "");
    Problem.check(!trade.isBlank() && trade.length() <= 64, "交易編號不正確");
    boolean simulated = "1".equals(p.get("SimulatePaid"));
    if ("1".equals(p.get("RtnCode")) && !simulated) orders.confirmOnline(id, amount, trade);
    // Never store credentials or complete payment payloads. Replayed valid notifications are
    // acknowledged.
    db.update(
        "insert into"
            + " payment_events(id,order_id,provider_trade_no,result_code,simulated,received_at)"
            + " values(?,?,?,?,?,?)",
        Ids.next(),
        id,
        trade,
        p.getOrDefault("RtnCode", "unknown"),
        simulated,
        System.currentTimeMillis());
    return "1|OK";
  }
}
