package com.example.governance.dictionary;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class DictionaryStoreTest {
    @Autowired DictionaryStore store;
    @Test void managesDirectoriesAndRejectsDuplicateEntries() {
        long root=store.createDirectory("存储测试目录",null);
        long child=store.createDirectory("用户信息",root);
        assertEquals(child,store.directoryByPath("存储测试目录/用户信息"));
        var entry=new DictionaryStore.Input(child,"store_test_status","用户状态","账号状态","varchar",20,null,"");
        long id=store.create(entry);
        assertEquals(1,store.page(root,"status",1,10).total());
        assertEquals(0,store.page(root,"missing",1,10).total());
        assertThrows(IllegalArgumentException.class,()->store.create(entry));
        assertThrows(IllegalArgumentException.class,()->store.deleteDirectory(child));
        assertThrows(IllegalArgumentException.class,()->store.update(id,new DictionaryStore.Input(child,"changed","用户状态","账号状态","varchar",20,null,"")));
        store.delete(id); store.deleteDirectory(child); store.deleteDirectory(root);
    }
    @Test void rejectsInvalidPrecision() {
        assertThrows(IllegalArgumentException.class,()->store.validate(new DictionaryStore.Input(1L,"amount","金额","交易金额","decimal",4,5,"")));
    }
    @Test void sortsAndMovesTreeAndRejectsCyclesAndStalePayload() {
        long a=store.createDirectory("排序字典A",null),b=store.createDirectory("排序字典B",null),child=store.createDirectory("字典子目录",a);
        var sorted=store.directories().stream().map(d->new com.example.governance.common.DirectorySorter.Item(d.id(),d.id()==child?Long.valueOf(b):d.parentId(),d.id()==b?0:1)).toList();
        store.sortDirectories(sorted);assertEquals(b,store.directories().stream().filter(d->d.id()==child).findFirst().orElseThrow().parentId());
        var cyclic=store.directories().stream().map(d->new com.example.governance.common.DirectorySorter.Item(d.id(),d.id()==b?Long.valueOf(child):d.parentId(),d.sortOrder())).toList();
        assertThrows(IllegalStateException.class,()->store.sortDirectories(cyclic));assertThrows(IllegalStateException.class,()->store.sortDirectories(java.util.List.of()));
        assertEquals(b,store.directories().stream().filter(d->d.id()==child).findFirst().orElseThrow().parentId());
    }
    @Test void directorySortRequiresUpdatePermissions() {
        assertEquals("datasource.dictionary.update",com.example.governance.security.PermissionCatalog.permissionFor(org.springframework.http.HttpMethod.POST,"/api/dictionary/directories/sort"));
        assertEquals("datasource.object_directory.update",com.example.governance.security.PermissionCatalog.permissionFor(org.springframework.http.HttpMethod.POST,"/api/object-directories/sort"));
        assertEquals("字典目录排序",com.example.governance.security.AuditCatalog.actionLabel("dictionary.directory.sort"));
        assertEquals("数据对象目录排序",com.example.governance.security.AuditCatalog.actionLabel("object.directory.sort"));
    }
}
