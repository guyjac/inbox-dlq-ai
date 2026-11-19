package com.example.inboxdemo.service;

import com.example.inboxdemo.model.InboxRecord;
import com.example.inboxdemo.model.InboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Saves inbox records as JSON files, one per message, named by message id.
 *
 * This is the class you would swap for a database table (or MongoDB) in production:
 * the file name plays the part of a unique constraint on the message id.
 * Writes go to a temp file and are moved into place, so nobody reads a half-written document.
 */
@Service
public class FileInbox {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path dir;
    private final ObjectMapper mapper;

    public FileInbox(@Value("${inbox.dir}") String dir, ObjectMapper mapper) {
        this.dir = Path.of(dir).toAbsolutePath();
        this.mapper = mapper;
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create inbox directory " + this.dir, e);
        }
    }

    /** Stores the record unless one with the same id exists. Returns false for a duplicate delivery. */
    public synchronized boolean insertIfAbsent(InboxRecord record) {
        if (Files.exists(fileFor(record.id))) {
            return false;
        }
        write(record);
        return true;
    }

    public synchronized void save(InboxRecord record) {
        write(record);
    }

    public synchronized Optional<InboxRecord> find(String id) {
        Path file = fileFor(id);
        return Files.exists(file) ? Optional.of(read(file)) : Optional.empty();
    }

    public synchronized List<InboxRecord> findAll() {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .sorted(Comparator.comparing((InboxRecord r) -> r.receivedAt))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<InboxRecord> findByStatus(InboxStatus status) {
        return findAll().stream().filter(r -> r.status == status).toList();
    }

    public synchronized void deleteAll() {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid message id '" + id + "': use letters, digits, '.', '_' or '-'");
        }
        return dir.resolve(id + ".json");
    }

    private InboxRecord read(Path file) {
        try {
            return mapper.readValue(file.toFile(), InboxRecord.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private void write(InboxRecord record) {
        Path target = fileFor(record.id);
        try {
            Path temp = Files.createTempFile(dir, ".inbox-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), record);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }
}
