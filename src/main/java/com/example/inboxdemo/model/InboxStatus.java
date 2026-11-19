package com.example.inboxdemo.model;

/**
 * Lifecycle of an inbox record.
 *
 * <pre>
 * RECEIVED ──► COMPLETE
 *          └─► FAIL ──► READY_FOR_RETRY ──► COMPLETE
 *                  │                    └─► FAIL
 *                  └──► NEEDS_REVIEW   (Claude would have to guess)
 * </pre>
 *
 * Only the healer sets READY_FOR_RETRY, and the replay service only looks at READY_FOR_RETRY.
 */
public enum InboxStatus {
    RECEIVED,
    COMPLETE,
    FAIL,
    READY_FOR_RETRY,
    NEEDS_REVIEW
}
