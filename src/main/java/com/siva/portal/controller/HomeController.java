package com.siva.portal.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@Controller
public class HomeController {

    private static final String UNKNOWN_ENDPOINT = "unknown";

    @GetMapping("/homepage/index.html")
    public String index(HttpServletRequest request, Model model) {
        log.info("Serving homepage/index.html is requested from {}", resolveClientHost(request));
        model.addAttribute("clientHost", resolveClientHost(request));
        return "index1.html";
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
        // Prefer standardized Forwarded header (RFC 7239)
        String forwarded = request.getHeader("Forwarded");
        String candidate = extractFromForwardedHeader(forwarded);
        if (StringUtils.hasText(candidate)) return candidate;

        // Common proxy headers used by load balancers/CDNs
        String[] headers = new String[]{
                "True-Client-IP",          // Akamai / some ADCs
                "CF-Connecting-IP",        // Cloudflare
                "X-Client-IP",             // Some proxies
                "X-Forwarded-Client-IP",   // Some proxies
                "X-Cluster-Client-IP",     // Rackspace / Heroku
                "X-Real-IP",               // Nginx
                "X-Forwarded-For",         // Standard de-facto (may be a list)
                "HTTP_X_FORWARDED_FOR",    // Legacy CGI-style
                "X-Originating-IP",        // Some mail/proxy chains
                "HTTP_CLIENT_IP",          // Legacy CGI-style
                "Proxy-Client-IP",         // Weblogic/Apache
                "WL-Proxy-Client-IP"       // WebLogic
        };

        for (String h : headers) {
            String v = request.getHeader(h);
            String ip = pickFirstAddressFromList(v);
            if (StringUtils.hasText(ip)) return ip;
        }
        return null;
    }

    private String extractFromForwardedHeader(String header) {
        if (!StringUtils.hasText(header)) return null;
        // Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43
        // Forwarded: for="[2001:db8:cafe::17]"; proto=https; by=203.0.113.43
        String[] commaGroups = header.split(",");
        for (String group : commaGroups) {
            String[] params = group.split(";\s*");
            for (String p : params) {
                String kv = p.trim();
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String key = kv.substring(0, eq).trim();
                if (!"for".equalsIgnoreCase(key)) continue;
                String val = kv.substring(eq + 1).trim();
                // Strip optional quotes
                if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                    val = val.substring(1, val.length() - 1);
                }
                // IPv6 may be in brackets [::1]
                if (val.startsWith("[") && val.endsWith("]")) {
                    val = val.substring(1, val.length() - 1);
                }
                // Remove optional :port
                int colon = val.lastIndexOf(':');
                if (colon > -1 && val.indexOf(':') == colon) { // single colon -> IPv4:port
                    String hostPart = val.substring(0, colon);
                    if (isValidAddressToken(hostPart)) return hostPart;
                }
                if (isValidAddressToken(val)) return val;
            }
        }
        return null;
    }

    private String pickFirstAddressFromList(String header) {
        if (!StringUtils.hasText(header)) return null;
        String[] parts = header.split(",");
        for (String part : parts) {
            String candidate = part.trim();
            // Strip optional spaces and quotes
            if (candidate.startsWith("\"") && candidate.endsWith("\"")) {
                candidate = candidate.substring(1, candidate.length() - 1);
            }
            // Remove :port for IPv4 host:port pattern
            int colon = candidate.lastIndexOf(':');
            if (colon > -1 && candidate.indexOf(':') == colon) {
                candidate = candidate.substring(0, colon);
            }
            if (isValidAddressToken(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isValidAddressToken(String token) {
        if (!StringUtils.hasText(token)) return false;
        String t = token.trim();
        return !"unknown".equalsIgnoreCase(t) && !"obfuscated".equalsIgnoreCase(t);
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
