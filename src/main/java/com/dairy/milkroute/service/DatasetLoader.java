package com.dairy.milkroute.service;

import com.dairy.milkroute.dto.DatasetConfig;
import com.dairy.milkroute.dto.Range;
import com.dairy.milkroute.dto.ReferenceData;
import com.dairy.milkroute.error.DatasetNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads dataset configs and reference data from {@code classpath:datasets/}.
 *
 * <p>The YAML is parsed into maps and then mapped across by hand rather than bound
 * reflectively onto the records. It is a few more lines, but a missing or misspelled key
 * fails here with the key name in the message, at load time, instead of arriving as a
 * zero that silently seeds a dairy with no villages in it.
 *
 * <p>SnakeYAML comes in with Spring Boot, so this adds no dependency.
 */
@Component
public class DatasetLoader {

    /** Dataset names become file paths, so they are restricted rather than trusted. */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    private static final String REFERENCE_FILE = "datasets/reference.yaml";

    public DatasetConfig load(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new DatasetNotFoundException("not a valid dataset name: " + name);
        }
        Map<String, Object> raw = read("datasets/%s.yaml".formatted(name));

        return new DatasetConfig(
                string(raw, "name"),
                longValue(raw, "seed"),

                integer(raw, "villageCount"),
                integer(raw, "corridorCount"),
                range(raw, "villageDistanceKm"),
                range(raw, "pointsPerVillage"),
                decimal(raw, "pointScatterMetres"),

                integer(raw, "targetFarmerCount"),
                decimal(raw, "twoFarmerPointRatio"),
                range(raw, "animalsPerFarmer"),
                range(raw, "litresPerAnimalPerDay"),

                integer(raw, "tankerCount"),
                intList(raw, "capacityMix"),
                integer(raw, "insulatedCount"),
                integer(raw, "driverCount"),

                integer(raw, "plantCount"),
                decimal(raw, "plantLat"),
                decimal(raw, "plantLng"),

                string(raw, "defaultSession"),
                decimal(raw, "defaultAmbientC"));
    }

    public ReferenceData loadReference() {
        Map<String, Object> raw = read(REFERENCE_FILE);

        List<ReferenceData.TemperatureRow> temperatures = new ArrayList<>();
        for (Map<String, Object> row : rows(raw, "temperatureProfiles")) {
            temperatures.add(new ReferenceData.TemperatureRow(
                    integer(row, "month"),
                    bigDecimal(row, "morning"),
                    bigDecimal(row, "evening")));
        }

        List<ReferenceData.ParameterRow> parameters = new ArrayList<>();
        for (Map<String, Object> row : rows(raw, "solverParameters")) {
            parameters.add(new ReferenceData.ParameterRow(
                    string(row, "key"),
                    bigDecimal(row, "value"),
                    string(row, "description")));
        }

        return new ReferenceData(temperatures, parameters);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new DatasetNotFoundException("no such dataset file on the classpath: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map)) {
                throw new DatasetNotFoundException(path + " is not a YAML mapping");
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new DatasetNotFoundException("could not read " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> raw, String key) {
        Object value = require(raw, key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("'%s' must be a list".formatted(key));
        }
        return (List<Map<String, Object>>) list;
    }

    @SuppressWarnings("unchecked")
    private Range range(Map<String, Object> raw, String key) {
        Object value = require(raw, key);
        if (!(value instanceof Map)) {
            throw new IllegalStateException("'%s' must be a { min, max } mapping".formatted(key));
        }
        Map<String, Object> bounds = (Map<String, Object>) value;
        return new Range(decimal(bounds, "min"), decimal(bounds, "max"));
    }

    private List<Integer> intList(Map<String, Object> raw, String key) {
        Object value = require(raw, key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("'%s' must be a list".formatted(key));
        }
        List<Integer> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(((Number) item).intValue());
        }
        return out;
    }

    private String string(Map<String, Object> raw, String key) {
        return String.valueOf(require(raw, key));
    }

    private int integer(Map<String, Object> raw, String key) {
        return ((Number) require(raw, key)).intValue();
    }

    private long longValue(Map<String, Object> raw, String key) {
        return ((Number) require(raw, key)).longValue();
    }

    private double decimal(Map<String, Object> raw, String key) {
        return ((Number) require(raw, key)).doubleValue();
    }

    private BigDecimal bigDecimal(Map<String, Object> raw, String key) {
        return new BigDecimal(String.valueOf(require(raw, key)));
    }

    private Object require(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new IllegalStateException("dataset is missing required key '%s'".formatted(key));
        }
        return value;
    }
}
