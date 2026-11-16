package com.example.governance.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class AccountPrincipal extends User {
    private final long id;
    private final String securityVersion;
    public AccountPrincipal(long id, String securityVersion, String username, String password, boolean enabled, Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.id = id;
        this.securityVersion = securityVersion;
    }
    public String securityVersion() { return securityVersion; }
    public long id() { return id; }
}
