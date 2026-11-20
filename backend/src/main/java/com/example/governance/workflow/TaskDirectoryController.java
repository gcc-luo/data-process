package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TaskDirectoryController {
    record DirectoryRequest(String name, Long parentId) {}
    record SortItem(long id, Long parentId, int sortOrder) {}
    record SortRequest(List<SortItem> items) {}
    private final TaskDirectoryStore directories;
    private final AuditLog audit;
    public TaskDirectoryController(TaskDirectoryStore directories, AuditLog audit) { this.directories = directories; this.audit = audit; }

    @GetMapping("/api/task-directories")
    List<TaskDirectoryStore.Directory> list() { return directories.list(); }

    @PostMapping("/api/task-directories")
    ResponseEntity<Map<String, Long>> create(@RequestBody DirectoryRequest request, Authentication auth) {
        try {
            long id = directories.create(request.name(), request.parentId());
            audit.success(auth, "directory.create", "task_directory", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录名称或父目录无效"); }
    }

    @PutMapping("/api/task-directories/{id}")
    ResponseEntity<Void> rename(@PathVariable long id, @RequestBody DirectoryRequest request, Authentication auth) {
        try { directories.rename(id, request.name()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录名称无效"); }
        audit.success(auth, "directory.update", "task_directory", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/task-directories/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        try { directories.delete(id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目录不存在"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
        audit.success(auth, "directory.delete", "task_directory", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/task-directories/sort")
    ResponseEntity<Void> sort(@RequestBody SortRequest request, Authentication auth) {
        try { directories.sort(request.items().stream().map(item -> new TaskDirectoryStore.Directory(item.id(), item.parentId(), null, item.sortOrder())).toList()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序数据无效"); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage()); }
        audit.success(auth, "directory.sort", "task_directory", 0);
        return ResponseEntity.noContent().build();
    }
}
