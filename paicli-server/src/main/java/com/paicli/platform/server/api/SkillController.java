package com.paicli.platform.server.api;

import com.paicli.platform.server.skill.SkillService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/v1/skills")
public class SkillController {
    private final SkillService skills;

    public SkillController(SkillService skills) { this.skills = skills; }

    @GetMapping
    public List<SkillService.SkillDescriptor> list(@RequestParam String projectKey) {
        return skills.list(projectKey);
    }

    @PostMapping("/imports")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillService.SkillDescriptor importSkill(@Valid @RequestBody ApiDtos.ImportSkillRequest request) {
        return skills.importFromGit(request.projectKey(), request.gitUrl(), request.name(), request.ref(),
                Boolean.TRUE.equals(request.global()));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestParam String projectKey,
                       @RequestParam(defaultValue = "false") boolean global) {
        if (!skills.delete(projectKey, name, global)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill not found");
        }
    }
}
