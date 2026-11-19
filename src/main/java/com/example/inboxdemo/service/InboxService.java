package com.example.inboxdemo.service;

import com.example.inboxdemo.model.InboxRecord;
import com.example.inboxdemo.model.InboxStatus;
import com.example.inboxdemo.service.ClaudeService.Fix;
import com.example.inboxdemo.service.ClaudeService.Healing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Owns putting data into the inbox, processing it, and every status change that follows.
 *
 * receive: persist first, then process. Once the record is in the inbox it cannot be lost,
 *          and a duplicate delivery of the same id is recognised and ignored.
 * process: apply the business rules, set COMPLETE or FAIL (with a readable error).
 * heal:    ask Claude for a fix to the flagged field, apply it, and flag the record READY_FOR_RETRY.
 *          If Claude would have to guess it sets NEEDS_REVIEW instead. A human is the real
 *          safety net here, and in production would approve before anything is retried.
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);
    private static final List<String> STATUSES = List.of("PLACED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");

    private final FileInbox inbox;
    private final ClaudeService claude;

    public InboxService(FileInbox inbox, ClaudeService claude) {
        this.inbox = inbox;
        this.claude = claude;
    }

    // ---- receive and process -------------------------------------------------------------

    public InboxRecord receive(String id, JsonNode data) {
        InboxRecord record = InboxRecord.received(id, data);
        if (!inbox.insertIfAbsent(record)) {
            log.info("[{}] duplicate delivery ignored", id);
            return inbox.find(id).orElseThrow();
        }
        return process(record);
    }

    /** Runs the business rules. Used for the first attempt and for every replay. */
    public InboxRecord process(InboxRecord record) {
        List<String> errors = validate(record.data);
        if (errors.isEmpty()) {
            record.status = InboxStatus.COMPLETE;
            record.error = null;
            log.info("[{}] COMPLETE", record.id);
        } else {
            record.status = InboxStatus.FAIL;
            record.error = String.join("; ", errors);
            log.warn("[{}] FAIL: {}", record.id, record.error);
        }
        inbox.save(record);
        return record;
    }

    // ---- healing -------------------------------------------------------------------------

    public List<InboxRecord> healAllFailed() {
        List<InboxRecord> healed = new ArrayList<>();
        for (InboxRecord failed : inbox.findByStatus(InboxStatus.FAIL)) {
            healed.add(heal(failed.id));
        }
        return healed;
    }

    public InboxRecord heal(String id) {
        InboxRecord record = inbox.find(id)
                .orElseThrow(() -> new NoSuchElementException("No message with id " + id));
        if (record.status != InboxStatus.FAIL) {
            throw new IllegalStateException(
                    "Message " + id + " is " + record.status + "; only FAIL messages can be healed");
        }

        Healing healing;
        try {
            healing = claude.suggestFixes(record.error, record.data, record.receivedAt);
        } catch (RuntimeException e) {
            // Claude could not be reached: leave the record exactly as it was, plus a note.
            log.warn("[{}] Claude call failed: {}", id, e.toString());
            record.healNote = "Claude unavailable, left as FAIL: " + shorten(e.getMessage());
            inbox.save(record);
            return record;
        }

        if (healing.fixes().isEmpty()) {
            record.status = InboxStatus.NEEDS_REVIEW;
            record.healNote = healing.explanation();
            log.info("[{}] NEEDS_REVIEW: {}", id, record.healNote);
        } else {
            ObjectNode fixed = record.data.deepCopy();
            for (Fix fix : healing.fixes()) {
                if (fix.field() != null && !fix.field().isBlank()) {
                    fixed.set(fix.field(), fix.value());
                }
            }
            record.data = fixed;
            record.status = InboxStatus.READY_FOR_RETRY;
            record.healNote = healing.explanation();
            log.info("[{}] READY_FOR_RETRY: {}", id, record.healNote);
        }
        inbox.save(record);
        return record;
    }

    // ---- the business rules ----------------------------------------------------------------
    // Error text names the field, the offending value and what was expected. It is read by a
    // person on screen and by Claude, so it needs to be specific.

    private List<String> validate(JsonNode data) {
        List<String> errors = new ArrayList<>();
        if (data == null || !data.isObject()) {
            errors.add("Data must be a JSON object");
            return errors;
        }

        for (String field : List.of("orderId", "customerId", "sku")) {
            JsonNode node = data.path(field);
            if (node.isMissingNode() || node.isNull()) {
                errors.add("Field %s: is required".formatted(field));
            } else if (!node.isTextual() || node.asText().isBlank()) {
                errors.add("Field %s: must be a non-empty string".formatted(field));
            }
        }

        Integer quantity = null;
        JsonNode q = data.path("quantity");
        if (q.isMissingNode() || q.isNull()) {
            errors.add("Field quantity: is required");
        } else if (!q.isInt()) {
            errors.add("Field quantity: '%s' is not a whole number".formatted(q.asText()));
        } else if (q.asInt() <= 0) {
            errors.add("Field quantity: must be greater than zero");
        } else {
            quantity = q.asInt();
        }

        BigDecimal unitPrice = money(data, "unitPrice", errors);
        BigDecimal total = money(data, "total", errors);
        if (quantity != null && unitPrice != null && total != null) {
            BigDecimal expected = unitPrice.multiply(BigDecimal.valueOf(quantity));
            if (expected.compareTo(total) != 0) {
                errors.add("Field total: %s does not equal quantity x unitPrice (%s)"
                        .formatted(total.toPlainString(), expected.toPlainString()));
            }
        }

        JsonNode date = data.path("deliveryDate");
        if (date.isMissingNode() || date.isNull()) {
            errors.add("Field deliveryDate: is required");
        } else {
            try {
                LocalDate.parse(date.asText());
            } catch (DateTimeParseException e) {
                errors.add("Field deliveryDate: '%s' is not a valid ISO-8601 date (expected yyyy-MM-dd)"
                        .formatted(date.asText()));
            }
        }

        JsonNode status = data.path("status");
        if (status.isMissingNode() || status.isNull()) {
            errors.add("Field status: is required");
        } else if (!STATUSES.contains(status.asText())) {
            errors.add("Field status: '%s' is not one of %s".formatted(status.asText(), STATUSES));
        }
        return errors;
    }

    private BigDecimal money(JsonNode data, String field, List<String> errors) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) {
            errors.add("Field %s: is required".formatted(field));
        } else if (!node.isNumber()) {
            errors.add("Field %s: '%s' is not a number".formatted(field, node.asText()));
        } else if (node.decimalValue().signum() < 0) {
            errors.add("Field %s: must not be negative".formatted(field));
        } else {
            return node.decimalValue();
        }
        return null;
    }

    private String shorten(String text) {
        if (text == null) {
            return "no details";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
