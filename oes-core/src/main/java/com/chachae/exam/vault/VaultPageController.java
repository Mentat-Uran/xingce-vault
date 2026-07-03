package com.chachae.exam.vault;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class VaultPageController {

  @GetMapping({"/", "/vault"})
  public String index() {
    return "redirect:/static/vault/app.html";
  }
}
