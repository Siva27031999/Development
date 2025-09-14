package com.siva.portal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    @GetMapping("/")
    public String index() {
        return "index.html";
    }

    @GetMapping("/portal")
    public String portal() {
        return "portal.html"; // Ensure portal.html exists in your templates/static folder
    }
}
