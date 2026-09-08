package com.dairy.milkroute.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The lookup data that is the same for every dataset: ambient temperature by month and
 * session, and the solver parameters.
 *
 * <p>Loaded from {@code classpath:datasets/reference.yaml}. It is seeded rather than
 * migrated because the reseed endpoint truncates both tables, and it is held in a file
 * rather than in the seeder because hard rule 4 puts tuning numbers outside code.
 *
 * @param temperatureProfiles one entry per month, carrying both sessions
 * @param solverParameters    every tuning number in the system
 */
public record ReferenceData(
        List<TemperatureRow> temperatureProfiles,
        List<ParameterRow> solverParameters) {

    public ReferenceData {
        temperatureProfiles = List.copyOf(temperatureProfiles);
        solverParameters = List.copyOf(solverParameters);
    }

    /**
     * @param month   1 to 12
     * @param morning ambient celsius at the morning collection hour
     * @param evening ambient celsius at the evening collection hour
     */
    public record TemperatureRow(int month, BigDecimal morning, BigDecimal evening) {
    }

    /**
     * @param key         parameter name, the natural primary key of solver_parameter
     * @param value       its value
     * @param description what it is for, carried into the table so the row explains itself
     */
    public record ParameterRow(String key, BigDecimal value, String description) {
    }
}
