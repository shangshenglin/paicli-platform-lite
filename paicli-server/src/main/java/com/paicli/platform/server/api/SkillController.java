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
        return skills.lifecycle(projectKey);
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

    @PostMapping("/imports/inspect")
    public SkillService.SkillInspection inspect(@Valid @RequestBody ApiDtos.ImportSkillRequest request){return skills.inspectFromGit(request.gitUrl(),request.name(),request.ref());}

    @PostMapping("/{name}/state")
    public SkillService.SkillDescriptor state(@PathVariable String name,@RequestParam String projectKey,
                                              @RequestParam(defaultValue="false")boolean global,
                                              @RequestParam boolean enabled,
                                              @RequestParam(defaultValue="false")boolean pinned){
        return skills.setState(projectKey,name,global,enabled,pinned);
    }

    @GetMapping("/{name}/files")
    public List<String> files(@PathVariable String name,@RequestParam String projectKey){return skills.fileManifest(projectKey,name);}

    @GetMapping("/{name}/updates")
    public SkillService.SkillUpdateStatus updates(@PathVariable String name,@RequestParam String projectKey,
                                                  @RequestParam(defaultValue="false")boolean global){return skills.checkForUpdate(projectKey,name,global);}
    @PostMapping("/{name}/upgrade")
    public SkillService.SkillDescriptor upgrade(@PathVariable String name,@RequestParam String projectKey,
                                                @RequestParam(defaultValue="false")boolean global){return skills.upgrade(projectKey,name,global);}
    @PostMapping("/{name}/rollback")
    public SkillService.SkillDescriptor rollback(@PathVariable String name,@RequestParam String projectKey,
                                                 @RequestParam(defaultValue="false")boolean global){return skills.rollback(projectKey,name,global);}
}
