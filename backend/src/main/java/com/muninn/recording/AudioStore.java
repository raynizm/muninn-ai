package com.muninn.recording;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Audio files at {@code {muninn.data-dir}/audio/{userId}/{id}.m4a}. Path parts are only ever UUIDs, so no
 * caller-supplied string reaches the filesystem. Writes are staged: the body streams into a temp file in the
 * same directory while being hashed, then {@link #place} hard-links it into place, which is atomic and never
 * replaces an existing file (a concurrent upload of the same id can therefore never clobber the winner).
 */
@Component
public class AudioStore {

    private static final int BUFFER = 64 * 1024;

    private final Path audioRoot;

    public AudioStore(@Value("${muninn.data-dir}") String dataDir) {
        this.audioRoot = Path.of(dataDir).toAbsolutePath().normalize().resolve("audio");
    }

    /** Thrown by {@link #stage} once more than the allowed number of bytes has been read. */
    public static final class TooLargeException extends RuntimeException {
        TooLargeException(long max) {
            super("audio exceeds " + max + " bytes");
        }
    }

    /** A fully written, hashed temp file; closing it deletes the temp name (a placed file keeps its own name). */
    public record Staged(Path temp, String sha256, long size) implements AutoCloseable {
        @Override
        public void close() {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public enum Placement {
        /** This call created the file. */
        CREATED,
        /** A file was already there with exactly the staged bytes; it was left alone. */
        EXISTS_IDENTICAL,
        /** A file was already there with different bytes; it was left alone. */
        EXISTS_DIFFERENT
    }

    public Path path(UUID userId, UUID id) {
        return userDir(userId).resolve(id + ".m4a");
    }

    /** Stages and places in one go; fails if the file already exists. Returns the final path. */
    public Path save(UUID userId, UUID id, InputStream in) {
        try (Staged staged = stage(userId, in, Long.MAX_VALUE)) {
            if (place(staged, userId, id) != Placement.CREATED) {
                throw new UncheckedIOException(
                        new FileAlreadyExistsException(path(userId, id).toString()));
            }
            return path(userId, id);
        }
    }

    /**
     * Streams {@code in} into a temp file next to the final location, hashing as it goes and syncing to disk.
     * On any failure (including {@link TooLargeException}) the temp file is removed before the exception leaves.
     */
    public Staged stage(UUID userId, InputStream in, long maxBytes) {
        Path temp = null;
        try {
            Path dir = Files.createDirectories(userDir(userId));
            temp = Files.createTempFile(dir, ".upload-", ".tmp");
            MessageDigest digest = sha256();
            long size = 0;
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    size += n;
                    if (size > maxBytes) {
                        throw new TooLargeException(maxBytes);
                    }
                    digest.update(buf, 0, n);
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                    while (bb.hasRemaining()) {
                        out.write(bb);
                    }
                }
                out.force(true);
            }
            return new Staged(temp, HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp, e);
            if (e instanceof IOException io) {
                throw new UncheckedIOException(io);
            }
            throw (RuntimeException) e;
        }
    }

    /** Atomically links the staged file to its final name unless a file is already there. */
    public Placement place(Staged staged, UUID userId, UUID id) {
        Path target = path(userId, id);
        try {
            try {
                try {
                    Files.createLink(target, staged.temp());
                } catch (UnsupportedOperationException noHardLinks) {
                    // Filesystems without hard links: a plain move refuses to replace an existing target.
                    Files.move(staged.temp(), target);
                }
                return Placement.CREATED;
            } catch (FileAlreadyExistsException e) {
                return sha256Of(target).equals(staged.sha256())
                        ? Placement.EXISTS_IDENTICAL
                        : Placement.EXISTS_DIFFERENT;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the file if present; a missing file is not an error. */
    public void delete(UUID userId, UUID id) {
        try {
            Files.deleteIfExists(path(userId, id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path userDir(UUID userId) {
        return audioRoot.resolve(userId.toString());
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path temp, Exception cause) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }
}
