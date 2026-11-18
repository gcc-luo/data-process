package com.example.governance.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class AccountPrincipal extends User {
    private final long id;
    private final String securityVersion;
    private final String nickname;
    private final boolean mustChangePassword;
    public AccountPrincipal(long id, String securityVersion, String username, String password, boolean enabled, Collection<? extends GrantedAuthority> authorities) {
        this(id, securityVersion, username, password, enabled, username, false, authorities);
    }
    public AccountPrincipal(long id, String securityVersion, String username, String password, boolean enabled, String nickname, boolean mustChangePassword, Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.id = id;
        this.securityVersion = securityVersion;
        this.nickname = nickname;
        this.mustChangePassword = mustChangePassword;
    }
    public String securityVersion() { return securityVersion; }
    public long id() { return id; }
    public String nickname() { return nickname; }
    public boolean mustChangePassword() { return mustChangePassword; }
}
