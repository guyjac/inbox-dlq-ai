package com.example.inboxdemo.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The document stored for each inbox message (one JSON file per message).
 *
 * Plain public fields on purpose: the file you open on screen and this class are obviously the same thing.
 * {@code data} is loosely typed JSON so a bad value such as "next Tuesday" is stored faithfully
 * and fails validation, rather than failing to parse.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InboxRecord {

    public String id;
    public InboxStatus status;
    public Instant receivedAt;

    /** The data the service works on. Replaced only when a heal is applied. */
    public JsonNode data;

    /** The data exactly as it arrived. Never modified, so before and after can be compared. */
    public JsonNode originalData;

    /** Why the last attempt failed. Claude is given this text. */
    public String error;

    /** Claude's one-line explanation of what it changed, or why it refused. */
    public String healNote;

    public InboxRecord() {
        // for Jackson
    }

    public static InboxRecord received(String id, JsonNode data) {
        InboxRecord record = new InboxRecord();
        record.id = id;
        record.status = InboxStatus.RECEIVED;
        record.receivedAt = Instant.now();
        record.data = data.deepCopy();
        record.originalData = data.deepCopy();
        return record;
    }
}
