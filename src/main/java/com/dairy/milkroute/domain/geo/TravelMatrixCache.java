package com.dairy.milkroute.domain.geo;

import com.dairy.milkroute.enums.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Holds computed travel matrices, keyed by a SHA-256 digest of everything that could change
 * their contents.
 *
 * <p>A matrix over 1,250 points is 1.5 million entries and takes real time to build.
 * Planning happens twice a day against the same geography, so building it once and reusing
 * it is most of the difference between a plan that returns in seconds and one that does not.
 *
 * <h2>Why a content hash rather than a version number</h2>
 *
 * <p>A cache that has to be invalidated by hand is a cache that will one day serve a stale
 * matrix, and a stale matrix is the worst kind of bug here: the distances are wrong but
 * entirely plausible, so the plan looks fine and the tanker is late. Hashing the inputs
 * means a village that moves, a point that is added, or a dataset that is swapped produces
 * a different key on its own, with nobody having to remember anything.
 *
 * <p>Three things go into the digest, and leaving any of them out would be a correctness
 * bug rather than a missed optimisation:
 *
 * <ul>
 *   <li>the sorted point set, so geography changes invalidate;
 *   <li>the session, because the same road is a different journey at 05:00 and 17:00;
 *   <li>the travel parameters, because retuning the circuity factor and re-planning is a
 *       demonstration the parameter table exists for, and it would prove nothing if the
 *       matrix came back unchanged.
 * </ul>
 *
 * <p>Clearing is therefore about memory, not correctness. Entries for a dataset that has
 * been reseeded away are unreachable rather than dangerous, but they still occupy space.
 */
public final class TravelMatrixCache {

    /**
     * Coordinates are hashed at six decimal places, matching the NUMERIC(9,6) columns they
     * come from. Hashing the raw double would make two points that are equal in the database
     * hash differently after a floating-point round trip.
     */
    private static final String COORDINATE_FORMAT = "%.6f,%.6f;";

    private final Map<String, TravelMatrix> byKey = new ConcurrentHashMap<>();

    /**
     * Returns the cached matrix for these inputs, building it with {@code build} on a miss.
     *
     * <p>{@code build} may run more than once if two threads miss at the same moment. That
     * is deliberate: the alternative is holding a lock across a multi-second computation,
     * and the two results would be identical anyway.
     */
    public TravelMatrix get(List<GeoPoint> points,
                            Session session,
                            TravelParameters parameters,
                            Supplier<TravelMatrix> build) {
        return byKey.computeIfAbsent(key(points, session, parameters), ignored -> build.get());
    }

    public void clear() {
        byKey.clear();
    }

    public int size() {
        return byKey.size();
    }

    /**
     * The digest. Points are sorted first so that the same set in a different order yields
     * the same key, which is what lets two callers share one matrix.
     */
    public static String key(List<GeoPoint> points, Session session, TravelParameters parameters) {
        StringBuilder material = new StringBuilder(points.size() * 24 + 128);

        points.stream()
                .distinct()
                .sorted(Comparator.comparingDouble(GeoPoint::lat)
                        .thenComparingDouble(GeoPoint::lng))
                .forEach(point -> material.append(
                        COORDINATE_FORMAT.formatted(point.lat(), point.lng())));

        material.append('|').append(session.name()).append('|')
                .append(parameters.circuityFactor()).append(',')
                .append(parameters.speedUnder2Km()).append(',')
                .append(parameters.speed2To10Km()).append(',')
                .append(parameters.speedOver10Km()).append(',')
                .append(parameters.morningSpeedFactor()).append(',')
                .append(parameters.eveningSpeedFactor());

        return sha256(material.toString());
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform, so this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
