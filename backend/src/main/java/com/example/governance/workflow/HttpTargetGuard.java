package com.example.governance.workflow;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF guard for HTTP source/sink nodes: resolves the target and rejects loopback, link-local,
 * site-local and cloud metadata addresses unless the host is explicitly allow-listed by platform config.
 */
public final class HttpTargetGuard {
    private final Set<String> allowedHosts;

    public HttpTargetGuard(String allowedHostsCsv) {
        var set = new java.util.HashSet<String>();
        if (allowedHostsCsv != null) for (String h : allowedHostsCsv.split(",")) {
            String host = h.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) set.add(host);
        }
        this.allowedHosts = Set.copyOf(set);
    }

    public void check(URI uri) {
        if (uri == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("HTTP 目标非法，仅支持 http/https");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(host)) return;
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (UnknownHostException ex) { throw new IllegalArgumentException("HTTP 目标主机无法解析: " + host); }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress()
                    || "169.254.169.254".equals(address.getHostAddress()))
                throw new IllegalArgumentException("HTTP 目标指向内部网络，已被安全策略拒绝: " + host);
        }
    }
}