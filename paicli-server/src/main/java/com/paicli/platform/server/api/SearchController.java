package com.paicli.platform.server.api;

import com.paicli.platform.server.search.GlobalSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/search")
public class SearchController {
    private final GlobalSearchService search;

    public SearchController(GlobalSearchService search) { this.search = search; }

    @GetMapping
    public List<GlobalSearchService.SearchResult> search(
            @RequestParam(defaultValue = "default") String projectKey,
            @RequestParam String query,
            @RequestParam(defaultValue = "50") int limit) {
        return search.search(projectKey, query, limit);
    }
}
