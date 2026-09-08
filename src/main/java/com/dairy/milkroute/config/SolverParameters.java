package com.dairy.milkroute.config;

import com.dairy.milkroute.entity.SolverParameter;
import com.dairy.milkroute.repository.SolverParameterRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Typed access to the {@code solver_parameter} table.
 *
 * <p>Twenty-one rows, read as a set rather than one key at a time: a lookup per parameter
 * would put a query inside the solver's inner loop. Callers take a {@link Snapshot} at the
 * start of a run and use it throughout, so every decision in that run is made against one
 * consistent set of numbers even if somebody retunes a parameter halfway through.
 *
 * <p>Deliberately not cached across calls. Retuning a parameter and immediately re-running
 * is the point of keeping these in the database, and a cache would break exactly the
 * demonstration the table exists for.
 */
@Component
public class SolverParameters {

    private final SolverParameterRepository repository;

    public SolverParameters(SolverParameterRepository repository) {
        this.repository = repository;
    }

    public Snapshot snapshot() {
        Map<String, Double> values = new HashMap<>();
        for (SolverParameter parameter : repository.findAll()) {
            values.put(parameter.getKey(), parameter.getValue().doubleValue());
        }
        return new Snapshot(values);
    }

    /**
     * One consistent read of every parameter.
     *
     * <p>A missing key throws rather than defaulting. A silent default would mean a
     * half-seeded database plans against numbers nobody chose, and the resulting routes
     * would look plausible while being wrong.
     */
    public record Snapshot(Map<String, Double> values) {

        public Snapshot {
            values = Map.copyOf(values);
        }

        public double get(String key) {
            Double value = values.get(key);
            if (value == null) {
                throw new IllegalStateException(
                        "solver parameter '%s' is not seeded".formatted(key));
            }
            return value;
        }

        public int getInt(String key) {
            return (int) Math.round(get(key));
        }

        public int size() {
            return values.size();
        }
    }
}
