package com.dairy.milkroute.controller;

import com.dairy.milkroute.dto.DatasetConfig;
import com.dairy.milkroute.dto.response.DatasetDiagnostics;
import com.dairy.milkroute.dto.response.SeedResult;
import com.dairy.milkroute.service.DatasetDiagnosticsService;
import com.dairy.milkroute.service.DatasetLoader;
import com.dairy.milkroute.service.ReseedService;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator endpoints for rebuilding and inspecting the seeded dairy.
 *
 * <p>Thin by design: the ordering that makes reseeding safe lives in
 * {@code ReseedService}, and the arithmetic behind the diagnostics lives in
 * {@code DatasetDiagnosticsService}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/admin")
public class AdminController {

    /** Reseeding destroys data, so it is confined to profiles where that is expected. */
    private static final Set<String> RESEED_PROFILES = Set.of("dev", "sim");

    private final DatasetLoader loader;
    private final ReseedService reseedService;
    private final DatasetDiagnosticsService diagnosticsService;
    private final Environment environment;
    private final String defaultDataset;

    public AdminController(DatasetLoader loader,
                           ReseedService reseedService,
                           DatasetDiagnosticsService diagnosticsService,
                           Environment environment,
                           @Value("${milkroute.dataset:baseline}") String defaultDataset) {
        this.loader = loader;
        this.reseedService = reseedService;
        this.diagnosticsService = diagnosticsService;
        this.environment = environment;
        this.defaultDataset = defaultDataset;
    }

    /**
     * Replaces the entire dairy with a different dataset.
     *
     * <p>The config is loaded here, before the service is called, so that an unknown
     * dataset name returns 404 with every existing row still in place.
     */
    @PostMapping("/reseed")
    public SeedResult reseed(@RequestParam String dataset) {
        DatasetConfig config = loader.load(dataset);
        requireReseedableProfile();
        return reseedService.reseed(config);
    }

    /**
     * Reports whether a dataset lands on the constraint it was written to hit. Read-only:
     * it inspects what is seeded rather than changing it.
     */
    @GetMapping("/dataset-check")
    public DatasetDiagnostics datasetCheck(
            @RequestParam(required = false) String dataset) {
        return diagnosticsService.check(loader.load(dataset == null ? defaultDataset : dataset));
    }

    private void requireReseedableProfile() {
        List<String> active = List.of(environment.getActiveProfiles());
        if (active.stream().noneMatch(RESEED_PROFILES::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "reseed is limited to the %s profiles; active profiles are %s"
                            .formatted(RESEED_PROFILES, active.isEmpty() ? "[none]" : active));
        }
    }
}
