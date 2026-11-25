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
}
