package com.dairy.milkroute.simulation;

import com.dairy.milkroute.error.DatasetNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads scenario files. Mapped by hand for the same reason dataset configs are: a
 * misspelled key should fail here, by name, rather than arrive as a zero that quietly makes
 * the simulation do nothing.
 */
@Component
@Profile("sim")
public class ScenarioLoader {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    @SuppressWarnings("unchecked")
    public ScenarioConfig load(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new DatasetNotFoundException("not a valid scenario name: " + name);
        }

        String path = "scenarios/%s.yaml".formatted(name);
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new DatasetNotFoundException("no such scenario: " + name);
        }

        Map<String, Object> raw;
        try (InputStream in = resource.getInputStream()) {
            raw = (Map<String, Object>) new Yaml().load(in);
        } catch (IOException e) {
            throw new DatasetNotFoundException("could not read " + path, e);
        }

        return new ScenarioConfig(
                string(raw, "name"),
                string(raw, "dataset"),
                string(raw, "session"),
                localDate(raw, "businessDate"),
                number(raw, "ambientC").doubleValue(),
                instant(raw, "startAt"),
                number(raw, "stepSeconds").intValue(),
                number(raw, "maxSteps").intValue(),
                number(raw, "offlineFromMinutes").intValue(),
                number(raw, "offlineUntilMinutes").intValue(),
                raw.get("ambientRiseAtMinutes") == null
                        ? null : ((Number) raw.get("ambientRiseAtMinutes")).intValue(),
                raw.get("ambientRiseToC") == null
                        ? null : ((Number) raw.get("ambientRiseToC")).doubleValue());
    }

    /**
     * A date, however SnakeYAML decided to represent it.
     *
     * <p>An unquoted {@code 2026-10-15} in YAML is parsed as a {@link java.util.Date}, not a
     * string, so parsing the {@code toString()} of it fails on "Thu Oct 15 00:00:00 GMT
     * 2026". Accepting both shapes is friendlier than a comment telling whoever writes the
     * next scenario to remember the quotes.
     */
    private static LocalDate localDate(Map<String, Object> raw, String key) {
        Object value = require(raw, key);
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static Instant instant(Map<String, Object> raw, String key) {
        Object value = require(raw, key);
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private static String string(Map<String, Object> raw, String key) {
        return String.valueOf(require(raw, key));
    }

    private static Number number(Map<String, Object> raw, String key) {
        return (Number) require(raw, key);
    }

    private static Object require(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new IllegalStateException("scenario is missing required key '%s'".formatted(key));
        }
        return value;
    }
}
