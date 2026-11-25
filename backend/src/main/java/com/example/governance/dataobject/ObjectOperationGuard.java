package com.example.governance.dataobject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Coordinates execution and manual recovery across application instances using the platform MySQL. */
@Component
public class ObjectOperationGuard {
    private final DataSource platform;
    private final ConcurrentHashMap<String,ReentrantLock> local=new ConcurrentHashMap<>();
    public ObjectOperationGuard(DataSource platform){this.platform=platform;}
    public Lease acquire(String operation) throws SQLException {
        if(operation==null||!operation.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("操作编号无效");
        Connection connection=platform.getConnection();
        try {
            if("MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                String name="data-object:"+operation;
                try(var statement=connection.prepareStatement("SELECT GET_LOCK(?, 0)")){statement.setString(1,name);try(var rows=statement.executeQuery()){if(!rows.next()||rows.getInt(1)!=1)throw new IllegalStateException("操作仍在执行，请稍后再核对");}}
                return ()->{try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,name);statement.executeQuery().close();}finally{connection.close();}};
            }
            connection.close();ReentrantLock lock=local.computeIfAbsent(operation,k->new ReentrantLock());if(!lock.tryLock())throw new IllegalStateException("操作仍在执行，请稍后再核对");return lock::unlock;
        }catch(Exception e){try{connection.close();}catch(SQLException ignored){}throw e;}
    }
    public interface Lease extends AutoCloseable { @Override void close() throws SQLException; }
}
