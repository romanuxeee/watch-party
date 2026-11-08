package org.watchparty.service;

import org.watchparty.config.AppProperties;
import org.watchparty.model.RoomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class MediaStorage {


    private static final Set<String> UPLOAD_EXTENSIONS = Set.of("mp4", "webm", "ogv", "mov", "m4v", "mkv");

    private final Path dir;
    private final int retentionHours;

    public MediaStorage(AppProperties props) {
        this.dir = Paths.get(props.media().dir()).toAbsolutePath().normalize();
        this.retentionHours = props.media().retentionHours();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось создать папку для видео: " + dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    public String saveUpload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new RoomException("Файл пустой.");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!UPLOAD_EXTENSIONS.contains(ext)) {
            throw new RoomException("Поддерживаются форматы: " + String.join(", ", UPLOAD_EXTENSIONS) + ".");
        }
        String name = UUID.randomUUID() + "." + ext;
        file.transferTo(dir.resolve(name));
        return name;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanup() {
        if (retentionHours <= 0) {
            return;
        }
        Instant threshold = Instant.now().minus(Duration.ofHours(retentionHours));
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(threshold)) {
                        Files.deleteIfExists(f);
                        log.info("Удалён старый файл: {}", f.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("Не удалось удалить {}: {}", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Не удалось просмотреть папку {}: {}", dir, e.getMessage());
        }
    }
}
