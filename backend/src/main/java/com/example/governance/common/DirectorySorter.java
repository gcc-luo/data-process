package com.example.governance.common;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validates the complete directory tree while the caller holds its directory lock. */
public final class DirectorySorter {
    public record Item(long id, Long parentId, int sortOrder) {}
    private DirectorySorter() {}
    public static void sort(JdbcTemplate jdbc,String table,List<Item> items){
        if(!Set.of("dictionary_directories","object_directories").contains(table)||items==null||items.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("排序数据无效");
        var rows=jdbc.query("SELECT id,name FROM "+table+" FOR UPDATE",(r,n)->Map.entry(r.getLong(1),r.getString(2)));Map<Long,String> names=new HashMap<>();rows.forEach(e->names.put(e.getKey(),e.getValue()));
        Map<Long,Item> byId=new HashMap<>();for(var item:items){if(item.sortOrder()<0||!names.containsKey(item.id())||byId.put(item.id(),item)!=null)throw new IllegalArgumentException("排序数据无效");}
        if(!byId.keySet().equals(names.keySet()))throw new IllegalStateException("目录已发生变化，请刷新后重试");
        Set<String> siblings=new HashSet<>();for(var item:items){Set<Long> seen=new HashSet<>();Item current=item;while(current.parentId()!=null){if(!seen.add(current.id()))throw new IllegalStateException("目录不能移入自身子孙");current=byId.get(current.parentId());if(current==null)throw new IllegalArgumentException("父目录不存在");}String key=Objects.toString(item.parentId(),"root")+"\u0000"+names.get(item.id());if(!siblings.add(key))throw new IllegalStateException("目标目录中已有同名目录");}
        for(var item:items)jdbc.update("UPDATE "+table+" SET parent_id=?,sort_order=? WHERE id=?",item.parentId(),item.sortOrder(),item.id());
    }
}
