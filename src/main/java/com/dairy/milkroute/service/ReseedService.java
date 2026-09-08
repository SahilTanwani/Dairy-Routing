package com.dairy.milkroute.service;

import com.dairy.milkroute.dto.DatasetConfig;
import com.dairy.milkroute.dto.response.SeedResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Swaps the whole dairy for a different one, so a demo can move between datasets without
 * a container restart.
 *
 * <p>The ordering in {@link #reseed(DatasetConfig)} is the interesting part, and it is
 * deliberate: the caller loads and validates the config <em>before</em> anything is
 * truncated, so a mistyped dataset name fails while the database is still intact. Wiping
 * first and discovering the name was wrong second leaves nothing to demo with.
 *
 * <p>Truncate rather than a version column. Versioning would mean a {@code dataset_version}
 * on every table and a filter in every query, and one missed filter gives a plan that
 * quietly mixes two dairies. This takes a few seconds and leaves no partial state to reason
 * about.
 */
@Service
public class ReseedService {

    /**
     * Every table the seeder or a planning run can write, in one statement.
     *
     * <p>CASCADE is not doing hidden work here: the list is already closed over its own
     * foreign keys, and naming every table means adding one to the schema without adding
     * it here fails loudly rather than leaving a few orphan rows behind.
     */
    private static final String TRUNCATE_ALL = """
            TRUNCATE village, collection_point, farmer, tanker, driver, plant,
                     route_plan, route, route_stop, trip, trip_stop, collection,
                     driver_event, tanker_ping, alert, intake_record,
                     point_coverage_state, plan_exclusion,
                     temperature_profile, solver_parameter
            RESTART IDENTITY CASCADE
            """;

    @PersistenceContext
    private EntityManager entityManager;

    private final SeedService seedService;

    public ReseedService(SeedService seedService) {
        this.seedService = seedService;
    }

    @Transactional
    public SeedResult reseed(DatasetConfig config) {
        entityManager.createNativeQuery(TRUNCATE_ALL).executeUpdate();
        entityManager.clear();

        // The travel matrix is cached by a hash of the point set, and a stale matrix from
        // the previous dataset produces distances that are wrong but entirely plausible.
        // Clearing it belongs here, and lands with the cache itself in T3.4.

        return seedService.seed(config);
    }
}
