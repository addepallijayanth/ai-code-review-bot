package com.acme.projects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projects;

    public ProjectController(ProjectRepository projects) {
        this.projects = projects;
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable long id, @AuthenticationPrincipal AppUser user) {
        return requireOwned(id, user);
    }

    @PutMapping("/{id}")
    public Project rename(@PathVariable long id, @RequestBody RenameRequest request, @AuthenticationPrincipal AppUser user) {
        Project project = requireOwned(id, user);
        project.rename(request.name());
        return projects.save(project);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUser user) {
        projects.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Project requireOwned(long id, AppUser user) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!project.getOwnerId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return project;
    }
}
