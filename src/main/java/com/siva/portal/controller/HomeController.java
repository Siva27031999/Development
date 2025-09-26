package com.siva.portal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    @GetMapping("/homepage/index.html")
    public String index() {
        return "index1.html";
    }
}