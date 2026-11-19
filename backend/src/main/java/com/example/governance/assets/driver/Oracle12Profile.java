package com.example.governance.assets.driver;

import org.springframework.stereotype.Component;

/** Oracle 12c and newer via ojdbc11; service-name URL form is the default. */
@Component
public final class Oracle12Profile extends AbstractOracleProfile {
    @Override public String id() { return "ORACLE:12c+"; }
    @Override public String displayName() { return "Oracle 12c 及以上"; }
    @Override public String defaultDriverKey() { return "builtin-ojdbc11"; }
    @Override String defaultConnectType() { return "SERVICE"; }
}
