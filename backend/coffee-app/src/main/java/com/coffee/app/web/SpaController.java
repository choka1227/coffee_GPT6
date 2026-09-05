package com.coffee.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
  @GetMapping({"/", "/login", "/orders", "/reports", "/branches", "/menu", "/accounts", "/roles"})
  public String page() {
    return "forward:/index.html";
  }
}
