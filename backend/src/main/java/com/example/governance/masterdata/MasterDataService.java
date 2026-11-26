package com.example.governance.masterdata;

import com.example.governance.assets.DataSourceStore;
import com.example.governance.dataobject.ObjectMetadata;
import com.example.governance.dataobject.ObjectStore;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Catalog and physical metadata for the separately authorized master-data workspace. */
@Service
public class MasterDataService {
    public record ObjectRef(long id, long directoryId, ObjectStore.Identity identity, String name, String sourceName) {}
    public record Tree(List<ObjectStore.Directory> directories, List<ObjectRef> objects) {}
    public record FieldView(int ordinal, String name, String type, int jdbcType, Integer length, Integer scale,
                            String nullable, String primaryKey, String autoIncrement, String generated,
                            String defaultValue, String remarks) {}
    public record ObjectDetail(ObjectRef object, List<FieldView> fields, boolean schemaStale,
                               boolean canInsert, boolean canEditDelete, String writeDisabledReason) {}

    private final ObjectStore objects;
    private final ObjectMetadata metadata;
    private final DataSourceStore sources;

    public MasterDataService(ObjectStore objects, ObjectMetadata metadata, DataSourceStore sources) {
        this.objects = objects;
        this.metadata = metadata;
        this.sources = sources;
    }

    public Tree tree() {
        Map<Long, String> sourceNames = sources.list().stream().collect(Collectors.toMap(
                DataSourceStore.Entry::id, entry -> entry.definition().name(), (left, right) -> left));
        List<ObjectRef> all = objects.all().stream().map(item -> new ObjectRef(
                item.id(), item.directoryId(), item.identity(), item.name(),
                sourceNames.getOrDefault(item.identity().sourceId(), "数据源 " + item.identity().sourceId())))
                .toList();
        return new Tree(objects.directories(), all);
    }

    public ObjectDetail detail(long objectId) throws SQLException, GeneralSecurityException {
        ObjectStore.Item item = objects.find(objectId);
        ObjectRef ref = treeObject(item);
        List<ObjectStore.Field> registered = objects.fields(objectId);
        List<ObjectMetadata.Column> physical = metadata.columns(item.identity());
        boolean stale = !sameSnapshot(registered, physical);
        List<FieldView> fields = physical.stream().map(column -> new FieldView(
                column.ordinal(), column.name(), column.type(), column.jdbcType(), column.length(), column.scale(),
                column.nullable(), column.primaryKey(), column.autoIncrement(), column.generated(),
                column.defaultValue(), column.remarks())).toList();
        boolean table = "TABLE".equals(item.identity().type());
        boolean hasKnownKey = fields.stream().anyMatch(field -> "YES".equals(field.primaryKey()))
                && fields.stream().noneMatch(field -> "UNKNOWN".equals(field.primaryKey()));
        boolean canInsert = table && !stale;
        boolean canEditDelete = canInsert && hasKnownKey;
        String reason = !table ? "视图只支持查看和导出"
                : stale ? "物理字段与登记信息不一致，请先刷新数据对象字段"
                : !hasKnownKey ? "未能确认主键，不能安全修改或删除记录" : "";
        return new ObjectDetail(ref, fields, stale, canInsert, canEditDelete, reason);
    }

    private ObjectRef treeObject(ObjectStore.Item item) {
        String sourceName = sources.find(item.identity().sourceId()).map(definition -> definition.name())
                .orElse("数据源 " + item.identity().sourceId());
        return new ObjectRef(item.id(), item.directoryId(), item.identity(), item.name(), sourceName);
    }

    private static boolean sameSnapshot(List<ObjectStore.Field> registered, List<ObjectMetadata.Column> physical) {
        if (registered.size() != physical.size()) return false;
        Map<String, ObjectStore.Field> expected = registered.stream().collect(Collectors.toMap(ObjectStore.Field::name, Function.identity()));
        for (ObjectMetadata.Column actual : physical) {
            ObjectStore.Field stored = expected.get(actual.name());
            if (stored == null || !stored.type().equalsIgnoreCase(actual.type())
                    || !Objects.equals(stored.length(), actual.length())
                    || !Objects.equals(stored.scale(), actual.scale())
                    || !Objects.equals(stored.primaryKey(), actual.primaryKey())) return false;
        }
        return true;
    }

    static String normalizedType(String type) { return type == null ? "" : type.trim().toUpperCase(Locale.ROOT); }
    static boolean trustedIdentifier(String value) { return value != null && !value.isBlank() && value.length() <= 255 && value.indexOf('\0') < 0; }
    static boolean registeredField(Set<String> registered, String field) { return registered != null && registered.contains(field); }
}
