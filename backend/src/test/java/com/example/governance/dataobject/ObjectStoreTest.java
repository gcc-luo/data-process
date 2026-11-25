package com.example.governance.dataobject;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ObjectStoreTest {
    @Autowired ObjectStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void managesTreeAndProtectsNonemptyDirectory() {
        long root=store.createDirectory("对象目录测试",null);
        long child=store.createDirectory("子目录",root);
        assertThrows(IllegalArgumentException.class,()->store.createDirectory("子目录",root));
        assertThrows(IllegalStateException.class,()->store.deleteDirectory(root));
        store.renameDirectory(child,"已修改");
        assertTrue(store.directories().stream().anyMatch(d->d.id()==child && d.name().equals("已修改")));
        store.deleteDirectory(child);store.deleteDirectory(root);
    }
    @Test void registersDistinctQualifiedTablesAndPaginatesDescendants() {
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (9898,'对象存储测试源','MYSQL','MYSQL:8.x','localhost',3306,'test','test','cipher')");
        long root=store.createDirectory("注册测试",null), child=store.createDirectory("子目录",root);
        var field=new ObjectStore.Field(1,"id","INT",null,null,"NO","YES",null);
        var first=new ObjectStore.Identity(9898,"test","schema_a","customers","TABLE");
        store.register(child,first,"客户","",java.util.List.of(field));
        store.register(child,new ObjectStore.Identity(9898,"test","schema_b","customers","TABLE"),"客户副本","",java.util.List.of(field));
        assertThrows(IllegalStateException.class,()->store.register(child,first,"重复","",java.util.List.of(field)));
        assertEquals(2,store.page(root,"customers","客户",1,1).total());
        assertEquals(1,store.page(root,"customers","客户",1,1).rows().size());
        assertThrows(IllegalStateException.class,()->store.deleteDirectory(child));
    }
    @Test void sortsAndMovesTreeWithoutCreatingDuplicateSiblings() {
        long a=store.createDirectory("排序对象A",null),b=store.createDirectory("排序对象B",null),child=store.createDirectory("重复名称",a);store.createDirectory("重复名称",b);
        var duplicate=store.directories().stream().map(d->new com.example.governance.common.DirectorySorter.Item(d.id(),d.id()==child?Long.valueOf(b):d.parentId(),d.sortOrder())).toList();
        assertThrows(IllegalStateException.class,()->store.sortDirectories(duplicate));assertEquals(a,store.directories().stream().filter(d->d.id()==child).findFirst().orElseThrow().parentId());
        var sorted=store.directories().stream().map(d->new com.example.governance.common.DirectorySorter.Item(d.id(),d.id()==child?null:d.parentId(),d.id()==child?0:d.sortOrder()+1)).toList();
        store.sortDirectories(sorted);assertNull(store.directories().stream().filter(d->d.id()==child).findFirst().orElseThrow().parentId());
    }
}
