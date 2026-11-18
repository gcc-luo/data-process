package com.example.governance.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Progressive login captcha: issued on demand, stored in the session, single-use with a two-minute expiry. */
@RestController
public class CaptchaController {
    public static final String SESSION_CODE = "loginCaptchaCode";
    public static final String SESSION_EXPIRES = "loginCaptchaExpires";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @GetMapping(value = "/api/captcha", produces = "image/svg+xml")
    ResponseEntity<String> captcha(HttpServletRequest request) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_CODE, code.toString());
        session.setAttribute(SESSION_EXPIRES, System.currentTimeMillis() + 120_000);
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\" viewBox=\"0 0 120 40\"><rect width=\"120\" height=\"40\" fill=\"#f2f6fc\"/>");
        for (int i = 0; i < 4; i++) {
            int x = 12 + i * 26 + RANDOM.nextInt(6), y = 26 + RANDOM.nextInt(8), rotation = RANDOM.nextInt(40) - 20;
            svg.append("<text x=\"").append(x).append("\" y=\"").append(y).append("\" font-size=\"24\" font-family=\"monospace\" fill=\"#3a6ea5\" transform=\"rotate(").append(rotation).append(" ").append(x).append(" ").append(y).append(")\">").append(code.charAt(i)).append("</text>");
        }
        for (int i = 0; i < 4; i++)
            svg.append("<line x1=\"").append(RANDOM.nextInt(120)).append("\" y1=\"").append(RANDOM.nextInt(40)).append("\" x2=\"").append(RANDOM.nextInt(120)).append("\" y2=\"").append(RANDOM.nextInt(40)).append("\" stroke=\"#9db8d8\" stroke-width=\"1\"/>");
        svg.append("</svg>");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(svg.toString());
    }
}
