package org.watchparty.service;

import org.watchparty.config.AppProperties;
import org.watchparty.model.RoomException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class YtDlpService {

    public record MediaResult(String fileName, String title) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(double percent, String message);
    }

    public static class YtDlpException extends RuntimeException {
        public YtDlpException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(YtDlpService.class);
    private static final Pattern PERCENT = Pattern.compile("\\[download]\\s+(\\d+(?:\\.\\d+)?)%");
    private static final long TITLE_TIMEOUT_SECONDS = 60;

    private static final class Job {
        final CompletableFuture<MediaResult> future = new CompletableFuture<>();
        final List<ProgressListener> listeners = new CopyOnWriteArrayList<>();

        void notifyProgress(double percent, String message) {
            for (ProgressListener l : listeners) {
                try {
                    l.onProgress(percent, message);
                } catch (Exception e) {
                    log.debug("Ошибка в обработчике прогресса: {}", e.getMessage());
                }
            }
        }
    }

    private final AppProperties.Ytdlp cfg;
    private final MediaStorage storage;
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final ConcurrentHashMap<String, Job> inFlight = new ConcurrentHashMap<>();

    public YtDlpService(AppProperties props, MediaStorage storage) {
        this.cfg = props.ytdlp();
        this.storage = storage;
        this.executor = Executors.newFixedThreadPool(Math.max(1, cfg.maxParallelDownloads()), daemon("ytdlp-worker"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemon("ytdlp-watchdog"));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        watchdog.shutdownNow();
    }


    public void validate(String rawUrl) {
        URI uri = parseHttpUrl(rawUrl);
        if (cfg.allowPrivateHosts()) {
            return;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
                    throw new RoomException("Ссылки на локальные адреса запрещены.");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new RoomException("Не удалось найти сайт по этой ссылке.");
        }
    }

    public static URI parseHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) {
            throw new RoomException("Вставьте корректную ссылку на видео.");
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new RoomException("Ссылка должна начинаться с http:// или https://");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new RoomException("Вставьте корректную ссылку на видео.");
        }
    }

    public CompletableFuture<MediaResult> download(String url, ProgressListener listener) {
        String id = hash(url.trim());
        Job job = new Job();
        job.listeners.add(listener);
        Job existing = inFlight.putIfAbsent(id, job);
        if (existing != null) {
            existing.listeners.add(listener);
            return existing.future;
        }
        executor.submit(() -> run(id, url.trim(), job));
        return job.future;
    }

    private void run(String id, String url, Job job) {
        try {
            Path cached = findFinalFile(id);
            if (cached != null) {
                Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                job.future.complete(new MediaResult(cached.getFileName().toString(), readTitle(id)));
                return;
            }

            job.notifyProgress(0, "Получаем информацию о видео");
            String title = fetchTitle(url);

            job.notifyProgress(0, "Скачиваем видео");
            downloadFile(id, url, job);

            Path file = findFinalFile(id);
            if (file == null) {
                throw new YtDlpException("yt-dlp завершился, но готовый файл не найден. Проверьте, что установлен ffmpeg.");
            }
            writeTitle(id, title);
            job.future.complete(new MediaResult(file.getFileName().toString(), title));
        } catch (YtDlpException e) {
            job.future.completeExceptionally(e);
        } catch (Exception e) {
            log.error("Ошибка загрузки {}", url, e);
            job.future.completeExceptionally(new YtDlpException("Не удалось скачать видео: " + e.getMessage()));
        } finally {
            inFlight.remove(id);
        }
    }

    private String fetchTitle(String url) throws IOException, InterruptedException {
        List<String> cmd = baseCommand();
        cmd.add("--simulate");
        cmd.add("--print");
        cmd.add("title");
        cmd.add("--");
        cmd.add(url);

        Process process = start(cmd);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> kill = scheduleKill(process, TITLE_TIMEOUT_SECONDS, timedOut);
        Deque<String> tail = new ArrayDeque<>();
        String title = null;
        try (BufferedReader reader = reader(process)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                remember(tail, line);
                if (title == null && !line.startsWith("ERROR") && !line.startsWith("WARNING")) {
                    title = line.trim();
                }
            }
        } finally {
            kill.cancel(false);
        }
        int code = process.waitFor();
        if (timedOut.get()) {
            throw new YtDlpException("Сайт слишком долго не отвечает. Попробуйте ещё раз.");
        }
        if (code != 0 || title == null) {
            throw new YtDlpException(errorText(tail));
        }
        return title;
    }

    private void downloadFile(String id, String url, Job job) throws IOException, InterruptedException {
        List<String> cmd = baseCommand();
        cmd.add("-f");
        cmd.add(format());
        cmd.add("--merge-output-format");
        cmd.add("mp4");
        cmd.add("--newline");
        cmd.add("--no-mtime");
        if (cfg.maxFilesizeMb() > 0) {
            cmd.add("--max-filesize");
            cmd.add(cfg.maxFilesizeMb() + "M");
        }
        cmd.add("-o");
        cmd.add(storage.dir().resolve(id + ".%(ext)s").toString());
        cmd.add("--");
        cmd.add(url);

        Process process = start(cmd);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> kill = scheduleKill(process, Math.max(30, cfg.timeoutSeconds()), timedOut);
        Deque<String> tail = new ArrayDeque<>();
        int streams = 0;
        int lastPercent = -1;
        try (BufferedReader reader = reader(process)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                remember(tail, line);
                if (line.contains("[download] Destination:")) {
                    streams++;
                    lastPercent = -1;
                    job.notifyProgress(0, streams == 1 ? "Скачиваем видео" : "Скачиваем звук");
                } else if (line.startsWith("[Merger]") || line.startsWith("[VideoRemuxer]")) {
                    job.notifyProgress(100, "Собираем файл");
                } else {
                    Matcher m = PERCENT.matcher(line);
                    if (m.find()) {
                        double percent = Double.parseDouble(m.group(1));
                        if ((int) percent != lastPercent) {
                            lastPercent = (int) percent;
                            job.notifyProgress(percent, streams <= 1 ? "Скачиваем видео" : "Скачиваем звук");
                        }
                    }
                }
            }
        } finally {
            kill.cancel(false);
        }
        int code = process.waitFor();
        if (timedOut.get()) {
            throw new YtDlpException("Превышено время загрузки (" + cfg.timeoutSeconds() + " с).");
        }
        if (code != 0) {
            throw new YtDlpException(errorText(tail));
        }
    }


    private List<String> baseCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.path());
        cmd.add("--no-playlist");
        cmd.add("--no-warnings");
        cmd.add("--no-colors");
        if (cfg.extraArgs() != null && !cfg.extraArgs().isBlank()) {
            for (String arg : cfg.extraArgs().trim().split("\\s+")) {
                cmd.add(arg);
            }
        }
        return cmd;
    }

    private String format() {
        if (cfg.format() != null && !cfg.format().isBlank()) {
            return cfg.format();
        }
        String h = cfg.maxHeight() > 0 ? "[height<=" + cfg.maxHeight() + "]" : "";
        return "bv*[vcodec^=avc1]" + h + "+ba[acodec^=mp4a]"
                + "/b[ext=mp4]" + h
                + "/b" + h
                + "/b";
    }

    private Process start(List<String> cmd) {
        try {
            return new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new YtDlpException("Не удалось запустить yt-dlp. Установите его и проверьте параметр "
                    + "app.ytdlp.path в application.properties (сейчас: " + cfg.path() + ").");
        }
    }

    private ScheduledFuture<?> scheduleKill(Process process, long seconds, AtomicBoolean timedOut) {
        return watchdog.schedule(() -> {
            timedOut.set(true);
            process.destroyForcibly();
        }, seconds, TimeUnit.SECONDS);
    }

    private static BufferedReader reader(Process process) {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void remember(Deque<String> tail, String line) {
        tail.addLast(line);
        while (tail.size() > 20) {
            tail.removeFirst();
        }
    }

    private static String errorText(Deque<String> tail) {
        String last = null;
        for (String line : tail) {
            if (line.startsWith("ERROR")) {
                last = line;
            }
        }
        if (last == null && !tail.isEmpty()) {
            last = tail.getLast();
        }
        if (last == null) {
            return "yt-dlp завершился с ошибкой.";
        }
        String text = last.replaceFirst("^ERROR:\\s*", "").trim();
        return "yt-dlp: " + (text.length() > 300 ? text.substring(0, 300) + "…" : text);
    }

    private Path findFinalFile(String id) throws IOException {
        Pattern p = Pattern.compile(Pattern.quote(id) + "\\.(mp4|webm|mkv|mov|m4v)");
        try (Stream<Path> files = Files.list(storage.dir())) {
            return files.filter(f -> p.matcher(f.getFileName().toString()).matches()).findFirst().orElse(null);
        }
    }

    private String readTitle(String id) {
        try {
            return Files.readString(storage.dir().resolve(id + ".title"), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "Видео";
        }
    }

    private void writeTitle(String id, String title) {
        try {
            Files.writeString(storage.dir().resolve(id + ".title"), title, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Не удалось сохранить название: {}", e.getMessage());
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
