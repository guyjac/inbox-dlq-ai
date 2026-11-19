package com.example.inboxdemo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The demo letters: one good, three healable broken variants, and one unhealable.
 *
 * Every defect is a genuine rule violation caught by InboxService's validation.
 * Nothing in the service is special-cased for demo messages.
 */
public final class DemoFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DemoFixtures() {
    }

    public record Letter(String id, ObjectNode data) {
    }

    /** Healable defects. Each is obvious at a glance and fixable from the message alone. */
    public enum Defect {
        BAD_DATE("bad-date"),
        BAD_STATUS("bad-status"),
        WORD_QUANTITY("word-quantity");

        private final String key;

        Defect(String key) {
            this.key = key;
        }

        public static Defect parse(String value) {
            for (Defect defect : values()) {
                if (defect.key.equals(value)) {
                    return defect;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown defect '" + value + "'. Use bad-date, bad-status or word-quantity.");
        }
    }

    /** A valid order. Flows straight through to COMPLETE. */
    public static Letter good() {
        return build("good", order -> {
        });
    }

    /** An order with one defect Claude can fix using only what is in the message. */
    public static Letter broken(Defect defect) {
        return build("broken", order -> {
            switch (defect) {
                case BAD_DATE -> order.put("deliveryDate", "next Tuesday");
                case BAD_STATUS -> order.put("status", "SHIPED");
                case WORD_QUANTITY -> {
                    order.put("quantity", "twelve");
                    order.put("total", 239.88);
                }
            }
        });
    }

    /** An order with no customerId. Fixing it would mean guessing, so Claude should decline. */
    public static Letter unhealable() {
        return build("unhealable", order -> order.remove("customerId"));
    }

    private static Letter build(String prefix, Consumer<ObjectNode> defect) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        ObjectNode order = MAPPER.createObjectNode();
        order.put("orderId", "ORD-" + suffix);
        order.put("customerId", "CUST-4821");
        order.put("sku", "SKU-BLUE-MUG");
        order.put("quantity", 3);
        order.put("unitPrice", 19.99);
        order.put("total", 59.97);
        order.put("deliveryDate", "2026-11-24");
        order.put("status", "PLACED");

        defect.accept(order);
        return new Letter(prefix + "-" + suffix, order);
    }
}
