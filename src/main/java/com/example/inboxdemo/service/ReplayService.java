package com.example.inboxdemo.service;

import com.example.inboxdemo.model.InboxRecord;
import com.example.inboxdemo.model.InboxStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the replay of data. It is deliberately dumb: it only touches records flagged READY_FOR_RETRY
 * and runs them through exactly the same processing as a new message. It has no opinion about why
 * anything failed. That judgement lives in the healer, which is the only thing that sets the flag.
 */
@Service
public class ReplayService {

    private final FileInbox inbox;
    private final InboxService inboxService;
    private final boolean schedulerEnabled;

    public ReplayService(FileInbox inbox,
                         InboxService inboxService,
                         @Value("${demo.scheduler.enabled:false}") boolean schedulerEnabled) {
        this.inbox = inbox;
        this.inboxService = inboxService;
        this.schedulerEnabled = schedulerEnabled;
    }

    public List<InboxRecord> replay() {
        List<InboxRecord> results = new ArrayList<>();
        for (InboxRecord record : inbox.findByStatus(InboxStatus.READY_FOR_RETRY)) {
            results.add(inboxService.process(record));
        }
        return results;
    }

    /** What production would run on a timer. Does nothing unless demo.scheduler.enabled=true. */
    @Scheduled(fixedDelayString = "${demo.scheduler.replay-interval-ms:10000}")
    public void scheduledReplay() {
        if (schedulerEnabled) {
            replay();
        }
    }
}
