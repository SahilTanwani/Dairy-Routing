package com.dairy.milkroute.dto.response;

/**
 * What a batch of driver events did.
 *
 * <p>{@code duplicates} is reported rather than hidden. A phone that reconnects and resends
 * forty events of which thirty-five were already known is behaving correctly, and the count
 * is how you can tell that from a phone that is malfunctioning.
 *
 * @param received   events in the batch
 * @param applied    events the server had not seen before
 * @param duplicates events it already held, rejected by the unique index on client_event_id
 * @param tripStatus where the trip stands after replaying them
 * @param currentSeq the stop the trip is at
 */
public record IngestResult(
        int received,
        int applied,
        int duplicates,
        String tripStatus,
        int currentSeq) {
}
