package com.coffee.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
    scanBasePackages = "com.coffee",
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
public class CoffeeApplication {
  public static void main(String[] args) {
    SpringApplication.run(CoffeeApplication.class, args);
  }
}
