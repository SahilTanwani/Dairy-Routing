package com.dairy.milkroute.config;

import com.dairy.milkroute.domain.geo.HaversineTravelTime;
import com.dairy.milkroute.domain.geo.TravelMatrixCache;
import com.dairy.milkroute.domain.geo.TravelParameters;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link TravelTimeProvider} from the current contents of {@code solver_parameter}.
 *
 * <p>This is the bridge, and it exists so that {@code domain/geo} does not have to know the
 * parameters live in a database. Everything on the domain side takes a plain
 * {@link TravelParameters} record through its constructor.
 *
 * <p>A provider is built per request rather than held as a singleton bean, for two reasons.
 * A singleton would read the parameter table while the context was starting, which on a
 * fresh database happens before the seeder has written a single row. And retuning a
 * parameter and immediately re-planning is the demonstration the table exists for, which a
 * provider captured at startup would quietly defeat.
 *
 * <p>The matrix cache is the one piece that <em>is</em> a singleton, because its whole value
 * is surviving across calls. That is safe because its keys include the parameters, so a
 * provider built from retuned parameters cannot collide with matrices built from the old
 * ones.
 */
@Component
public class TravelTimeFactory {

    private final SolverParameters parameters;
    private final TravelMatrixCache matrixCache;

    public TravelTimeFactory(SolverParameters parameters, TravelMatrixCache matrixCache) {
        this.parameters = parameters;
        this.matrixCache = matrixCache;
    }

    /** A provider bound to the parameter values as they stand right now. */
    public TravelTimeProvider create() {
        return new HaversineTravelTime(travelParameters(), matrixCache);
    }

    public TravelParameters travelParameters() {
        SolverParameters.Snapshot snapshot = parameters.snapshot();
        return new TravelParameters(
                snapshot.get("circuityFactor"),
                snapshot.get("speedUnder2Km"),
                snapshot.get("speed2To10Km"),
                snapshot.get("speedOver10Km"),
                snapshot.get("morningSpeedFactor"),
                snapshot.get("eveningSpeedFactor"));
    }

    /**
     * The cache is registered here rather than annotated, because {@link TravelMatrixCache}
     * lives in {@code domain/} and nothing in there carries a Spring annotation.
     */
    @Configuration
    static class CacheConfiguration {

        @Bean
        TravelMatrixCache travelMatrixCache() {
            return new TravelMatrixCache();
        }
    }
}
