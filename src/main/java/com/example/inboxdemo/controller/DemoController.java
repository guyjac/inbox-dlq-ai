package com.example.inboxdemo.controller;

import com.example.inboxdemo.controller.DemoFixtures.Defect;
import com.example.inboxdemo.controller.DemoFixtures.Letter;
import com.example.inboxdemo.model.InboxRecord;
import com.example.inboxdemo.service.FileInbox;
import com.example.inboxdemo.service.InboxService;
import com.example.inboxdemo.service.ReplayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Demo API: one endpoint per beat of the walkthrough, so the presenter controls the pacing.
 * It holds no logic; it calls the same services a scheduler would.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final InboxService inboxService;
    private final ReplayService replayService;
    private final FileInbox inbox;

    public DemoController(InboxService inboxService, ReplayService replayService, FileInbox inbox) {
        this.inboxService = inboxService;
        this.replayService = replayService;
        this.inbox = inbox;
    }

    // ---- put messages in the inbox ----

    @PostMapping("/messages/good")
    public InboxRecord sendGood() {
        Letter letter = DemoFixtures.good();
        return inboxService.receive(letter.id(), letter.data());
    }

    @PostMapping("/messages/broken")
    public InboxRecord sendBroken(@RequestParam(name = "defect", defaultValue = "bad-date") String defect) {
        Letter letter = DemoFixtures.broken(Defect.parse(defect));
        return inboxService.receive(letter.id(), letter.data());
    }

    @PostMapping("/messages/unhealable")
    public InboxRecord sendUnhealable() {
        Letter letter = DemoFixtures.unhealable();
        return inboxService.receive(letter.id(), letter.data());
    }

    // ---- look at the inbox ----

    @GetMapping("/messages")
    public List<InboxRecord> list() {
        return inbox.findAll();
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<InboxRecord> get(@PathVariable("id") String id) {
        return inbox.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ---- heal and replay ----

    /** Asks Claude to fix every FAIL record. */
    @PostMapping("/heal")
    public Map<String, Object> heal() {
        List<InboxRecord> records = inboxService.healAllFailed();
        String summary = records.isEmpty()
                ? "Nothing to heal: no records are in FAIL."
                : "Looked at %d failed record(s).".formatted(records.size());
        return Map.of("summary", summary, "records", records);
    }

    @PostMapping("/heal/{id}")
    public InboxRecord healOne(@PathVariable("id") String id) {
        return inboxService.heal(id);
    }

    /** Replays READY_FOR_RETRY records only. FAIL records are left alone. */
    @PostMapping("/replay")
    public Map<String, Object> replay() {
        List<InboxRecord> records = replayService.replay();
        String summary = records.isEmpty()
                ? "Nothing to replay: no records are flagged READY_FOR_RETRY. FAIL records are left alone."
                : "Replayed %d record(s).".formatted(records.size());
        return Map.of("summary", summary, "records", records);
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        inbox.deleteAll();
        return Map.of("summary", "Inbox cleared.");
    }

    // ---- errors ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
