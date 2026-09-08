package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ReadinessState;
import com.dairy.milkroute.dto.response.SeedResult;
import com.dairy.milkroute.repository.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fills an empty database at startup, so that a fresh {@code docker compose up} produces a
 * working dairy rather than a schema with nothing in it.
 *
 * <p>Seeding is skipped whenever villages already exist. That check is what makes a
 * restart cheap and what stops a container bounce from doubling the fleet; the deliberate
 * way to rebuild the dairy is the reseed endpoint, which truncates first.
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final VillageRepository villageRepo;
    private final DatasetLoader loader;
    private final SeedService seedService;
    private final String datasetName;
    private final ReadinessState readiness;

    public SeedRunner(VillageRepository villageRepo,
                      DatasetLoader loader,
                      SeedService seedService,
                      ReadinessState readiness,
                      @Value("${milkroute.dataset:baseline}") String datasetName) {
        this.villageRepo = villageRepo;
        this.loader = loader;
        this.seedService = seedService;
        this.readiness = readiness;
        this.datasetName = datasetName;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existing = villageRepo.count();
        if (existing > 0) {
            log.info("Database already holds {} villages; skipping seed", existing);
            readiness.markReady();
            return;
        }

        SeedResult result = seedService.seed(loader.load(datasetName));
        log.info("Seeded '{}' (seed {}): {} villages, {} points, {} farmers, {} tankers, "
                        + "{} drivers, {} plants in {} ms",
                result.dataset(), result.seed(), result.villages(), result.points(),
                result.farmers(), result.tankers(), result.drivers(), result.plants(),
                result.elapsedMs());

        // Only now is the application genuinely able to answer. Until this line the
        // solver parameters do not exist and every planning call would fail.
        readiness.markReady();
    }
}
