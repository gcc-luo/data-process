package com.example.governance.security;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory online session inventory for the single-node deployment; entries die with their session. */
@Component
public class OnlineSessions implements HttpSessionListener {
    public record Online(String sessionId, String username, String nickname, String ip, String userAgent, long loginAt) {}
    private final Map<String, Online> sessions = new ConcurrentHashMap<>();

    public void register(HttpSession session, AccountPrincipal principal, String ip, String userAgent) {
        sessions.put(session.getId(), new Online(session.getId(), principal.getUsername(), principal.nickname(), ip, userAgent, System.currentTimeMillis()));
    }
    public List<Online> list() {
        return sessions.values().stream().sorted(Comparator.comparingLong(Online::loginAt).reversed()).toList();
    }
    public void remove(String sessionId) { sessions.remove(sessionId); }
    @Override public void sessionDestroyed(HttpSessionEvent event) { sessions.remove(event.getSession().getId()); }
}
