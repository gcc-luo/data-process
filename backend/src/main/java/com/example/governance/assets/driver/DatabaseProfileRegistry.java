package com.example.governance.assets.driver;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Collects all profile strategies; lookup by id and catalog for the form API. */
public final class DatabaseProfileRegistry {
    public record ParamView(String key, String label, String kind, List<String> values, String hint) {}
    public record ProfileView(String id, String type, String displayName, int defaultPort, String driverClassName, String defaultDriverKey, List<DatabaseProfile.ExtraField> extraFields, List<ParamView> extraParams) {}
    private final Map<String, DatabaseProfile> byId;
    public DatabaseProfileRegistry(List<DatabaseProfile> profiles) {
        var map = new java.util.HashMap<String, DatabaseProfile>();
        for (DatabaseProfile profile : profiles) {
            if (map.putIfAbsent(profile.id(), profile) != null) throw new IllegalArgumentException("Duplicate database profile id: " + profile.id());
        }
        byId = Map.copyOf(map);
    }
    public DatabaseProfile resolve(String id) {
        DatabaseProfile profile = byId.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown database profile: " + id);
        return profile;
    }
    public List<DatabaseProfile> all() { return byId.values().stream().sorted(Comparator.comparing(DatabaseProfile::id)).toList(); }
    public Set<String> types() { return all().stream().map(DatabaseProfile::type).collect(Collectors.toCollection(LinkedHashSet::new)); }
    public List<ProfileView> views() {
        return all().stream().map(p -> new ProfileView(p.id(), p.type(), p.displayName(), p.defaultPort(), p.driverClassName(), p.defaultDriverKey(), p.extraFields(),
            p.extraParams().values().stream().sorted(Comparator.comparing(DatabaseProfile.ParamSpec::key))
                .map(s -> new ParamView(s.key(), s.label(), s.kind().name(), s.values(), s.hint())).toList())).toList();
    }
}
