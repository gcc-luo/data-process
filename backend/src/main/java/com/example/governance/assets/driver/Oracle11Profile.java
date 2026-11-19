package com.example.governance.assets.driver;

import org.springframework.stereotype.Component;

/** Oracle 11g (11.2.0.4+) via ojdbc8; SID URL form is the default for this generation. */
@Component
public final class Oracle11Profile extends AbstractOracleProfile {
    @Override public String id() { return "ORACLE:11g"; }
    @Override public String displayName() { return "Oracle 11g"; }
    @Override public String defaultDriverKey() { return "builtin-ojdbc8"; }
    @Override String defaultConnectType() { return "SID"; }
}
