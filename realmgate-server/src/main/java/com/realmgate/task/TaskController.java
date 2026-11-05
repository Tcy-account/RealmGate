package com.realmgate.task;

import com.realmgate.common.api.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService service;

    @PostMapping
    public ApiResponse<Task> create(@PathVariable Long projectId, @RequestBody Task task) {
        task.setProjectId(projectId);   // 从路径取 projectId 覆盖 body，防止越权指定别的 projectId
        return service.create(task);
    }

    @GetMapping
    public ApiResponse<List<Task>> listByProject(@PathVariable Long projectId) {
        return service.listByProject(projectId);
    }

    @GetMapping("/{id}")
    public ApiResponse<Task> getById(@PathVariable Long projectId, @PathVariable Long id) {
        return service.getById(projectId, id);
    }

    @PutMapping("/{id}")
    public ApiResponse<Task> update(@PathVariable Long projectId, @PathVariable Long id, @RequestBody Task task) {
        return service.update(projectId, id, task);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long projectId, @PathVariable Long id) {
        return service.remove(projectId, id);
    }
}
