package com.siva.portal.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Controller
public class HomeController {

    private static final String UNKNOWN_ENDPOINT = "unknown";

    @GetMapping("/homepage/index.html")
    public String index(HttpServletRequest request, Model model) {
        model.addAttribute("clientHost", resolveClientHost(request));
        return "index1.html";
    }

    @GetMapping("/homepage/index2.html")
    public String index2(HttpServletRequest request, Model model) {
        model.addAttribute("clientHost", resolveClientHost(request));
        return "index2.html";
    }

    private String resolveClientHost(HttpServletRequest request) {
        // 1) If upstream proxy provides hostname explicitly, trust it
        String named = headerFirst(request, "X-Client-Hostname", "X-End-User-Hostname", "X-Remote-Host");
        if (StringUtils.hasText(named)) {
            return named.trim();
        }

        // 2) Determine client IP considering proxies
        String candidate = firstForwardedAddress(request);
        if (!StringUtils.hasText(candidate)) {
            candidate = request.getRemoteAddr();
        }
        if (!StringUtils.hasText(candidate)) {
            return UNKNOWN_ENDPOINT;
        }
        candidate = stripScopeId(candidate.trim());

        // 3) Local/loopback: return this machine's hostname for local dev
        if (isLocalOrLoopback(candidate)) {
            String local = localMachineHostname();
            return StringUtils.hasText(local) ? local : "localhost";
        }

        // 4) Reverse-DNS the IP to a hostname when possible
        try {
            InetAddress address = InetAddress.getByName(candidate);
            String host = address.getCanonicalHostName();
            if (!StringUtils.hasText(host) || host.equalsIgnoreCase(candidate)) {
                host = address.getHostName();
            }
            return StringUtils.hasText(host) ? host : candidate;
        } catch (UnknownHostException ex) {
            return candidate;
        }
    }

    private String firstForwardedAddress(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(header)) {
            header = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String[] parts = header.split(",");
        for (String part : parts) {
            String candidate = part.trim();
            if (candidate.length() > 0 && !"unknown".equalsIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String stripScopeId(String address) {
        int scopeIndex = address.indexOf('%');
        return scopeIndex > 0 ? address.substring(0, scopeIndex) : address;
    }

    private boolean isLocalOrLoopback(String addr) {
        if (!StringUtils.hasText(addr)) return true;
        String a = addr.trim();
        return "127.0.0.1".equals(a)
                || "::1".equals(a)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(a)
                || "localhost".equalsIgnoreCase(a);
    }

    private String localMachineHostname() {
        try {
            String hn = InetAddress.getLocalHost().getHostName();
            if (StringUtils.hasText(hn)) return hn;
        } catch (Exception ignored) {}
        String envWin = System.getenv("COMPUTERNAME");
        if (StringUtils.hasText(envWin)) return envWin;
        String envNix = System.getenv("HOSTNAME");
        if (StringUtils.hasText(envNix)) return envNix;
        return null;
    }

    private String headerFirst(HttpServletRequest req, String... names) {
        if (names == null) return null;
        for (String n : names) {
            String v = req.getHeader(n);
            if (StringUtils.hasText(v) && !"unknown".equalsIgnoreCase(v.trim())) {
                return v.trim();
            }
        }
        return null;
    }
}
