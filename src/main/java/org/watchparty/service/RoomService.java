package org.watchparty.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.watchparty.config.AppProperties;
import org.watchparty.model.ChatMessage;
import org.watchparty.model.Participant;
import org.watchparty.model.Room;
import org.watchparty.model.RoomException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вся логика комнат: создание, вход/выход, синхронизация плеера, чат, загрузка видео.
 * Аналог TCP-сервера из исходного C#-приложения, но поверх HTTP/WebSocket.
 */
@Service
@Slf4j
public class RoomService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Set<String> DIRECT_EXTENSIONS = Set.of("mp4", "webm", "ogv", "mov", "m4v");
    private static final int MAX_CHAT_LENGTH = 500;

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final YtDlpService ytDlp;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Participant> bySession = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public RoomService(AppProperties props, ObjectMapper mapper, YtDlpService ytDlp) {
        this.props = props;
        this.mapper = mapper;
        this.ytDlp = ytDlp;
    }


    public Room createRoom() {
        for (int i = 0; i < 50; i++) {
            String code = randomCode();
            if (rooms.putIfAbsent(code, new Room(code)) == null) {
                log.info("Создана комната {}", code);
                return rooms.get(code);
            }
        }
        throw new RoomException("Не удалось создать комнату, попробуйте ещё раз.", 500);
    }

    public Room getRoom(String rawCode) {
        String code = normalizeCode(rawCode);
        Room room = rooms.get(code);
        if (room == null) {
            throw new RoomException("Комната с кодом " + code + " не найдена. "
                    + "Проверьте код и убедитесь, что хост создал комнату.", 404);
        }
        return room;
    }

    public Participant join(WebSocketSession session, String rawCode, String rawName) {
        if (bySession.containsKey(session.getId())) {
            throw new RoomException("Вы уже находитесь в комнате.");
        }
        Room room = getRoom(rawCode);
        String name = sanitizeName(rawName);
        Participant p = room.addParticipant(name, session, props.room().maxParticipants());
        bySession.put(session.getId(), p);
        log.info("{} вошёл в комнату {}", p.getName(), room.getCode());

        p.send(json(msg("JOINED",
                "code", room.getCode(),
                "you", Map.of("id", p.getId(), "name", p.getName(), "token", p.getToken()),
                "guestControl", props.room().guestControl(),
                "state", room.state(),
                "participants", room.participantViews(),
                "history", room.history())));
        broadcastParticipants(room);
        broadcast(room, msg("SYSTEM", "text", p.getName() + " присоединился"));
        return p;
    }

    public void leave(String sessionId) {
        Participant p = bySession.remove(sessionId);
        if (p == null) {
            return;
        }
        Room room = rooms.get(p.getRoomCode());
        if (room == null) {
            return;
        }
        Participant newHost = room.removeParticipant(p.getId());
        log.info("{} вышел из комнаты {}", p.getName(), room.getCode());
        if (room.isEmpty()) {
            return;
        }
        broadcast(room, msg("SYSTEM", "text", p.getName() + " вышел"));
        if (newHost != null) {
            broadcast(room, msg("SYSTEM", "text", newHost.getName() + " теперь хост комнаты"));
        }
        broadcastParticipants(room);
    }


    public void handle(String sessionId, JsonNode message) {
        Participant p = bySession.get(sessionId);
        if (p == null) {
            throw new RoomException("Сначала войдите в комнату.");
        }
        Room room = rooms.get(p.getRoomCode());
        if (room == null) {
            throw new RoomException("Комната закрыта.");
        }
        String type = message.path("type").asText("");
        switch (type) {
            case "PLAY" -> {
                requireControl(room, p);
                requireVideo(room);
                room.play(position(message));
                broadcast(room, stateMessage(room, "PLAY", p));
            }
            case "PAUSE" -> {
                requireControl(room, p);
                requireVideo(room);
                room.pause(position(message));
                broadcast(room, stateMessage(room, "PAUSE", p));
            }
            case "SEEK" -> {
                requireControl(room, p);
                requireVideo(room);
                room.seek(position(message));
                broadcast(room, stateMessage(room, "SEEK", p));
            }
            case "SYNC_REQUEST" -> p.send(json(stateMessage(room, "SYNC", null)));
            case "LOAD_VIDEO" -> loadVideo(room, p, message.path("url").asText(""));
            case "CHAT" -> chat(room, p, message.path("text").asText(""));
            case "PING" -> p.send(json(msg("PONG")));
            default -> throw new RoomException("Неизвестная команда: " + type);
        }
    }

    private void chat(Room room, Participant p, String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_CHAT_LENGTH) {
            text = text.substring(0, MAX_CHAT_LENGTH);
        }
        ChatMessage chat = new ChatMessage(p.getName(), text, System.currentTimeMillis());
        room.addChat(chat);
        broadcast(room, msg("CHAT", "userId", p.getId(), "user", chat.user(), "text", chat.text(), "time", chat.time()));
    }

    private void loadVideo(Room room, Participant p, String rawUrl) {
        requireControl(room, p);
        URI uri = YtDlpService.parseHttpUrl(rawUrl);
        String url = rawUrl.trim();

        // Прямая ссылка на файл — отдаём браузерам как есть
        String path = uri.getPath() == null ? "" : uri.getPath();
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (DIRECT_EXTENSIONS.contains(ext)) {
            String title = path.substring(path.lastIndexOf('/') + 1);
            room.setVideo(url, title);
            broadcast(room, stateMessage(room, "VIDEO", p));
            broadcast(room, msg("SYSTEM", "text", p.getName() + " включил(а) видео: " + title));
            return;
        }

        // YouTube и остальные сайты через yt-dlp
        ytDlp.validate(url);
        if (!room.tryStartLoading()) {
            throw new RoomException("В комнате уже идёт загрузка видео. Дождитесь её окончания.");
        }
        broadcast(room, msg("SYSTEM", "text", p.getName() + " запустил(а) загрузку видео"));
        broadcast(room, msg("LOADING", "active", true, "percent", 0, "message", "Готовим видео"));

        ytDlp.download(url, (percent, message) ->
                broadcast(room, msg("LOADING", "active", true, "percent", Math.round(percent), "message", message))
        ).whenComplete((media, error) -> {
            room.finishLoading();
            broadcast(room, msg("LOADING", "active", false));
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                broadcast(room, msg("ERROR", "message", cause.getMessage()));
                return;
            }
            room.setVideo("/media/" + media.fileName(), media.title());
            broadcast(room, stateMessage(room, "VIDEO", p));
            broadcast(room, msg("SYSTEM", "text", "Видео готово: " + media.title()));
        });
    }

    public Participant authorizeUpload(String rawCode, String token) {
        Room room = getRoom(rawCode);
        for (Participant p : room.participants()) {
            if (p.getToken().equals(token)) {
                if (!props.room().guestControl() && !room.isHost(p.getId())) {
                    throw new RoomException("Менять видео может только хост комнаты.", 403);
                }
                return p;
            }
        }
        throw new RoomException("Нет доступа к комнате. Обновите страницу и войдите заново.", 403);
    }

    public void attachUploadedVideo(Participant p, String fileName, String title) {
        Room room = getRoom(p.getRoomCode());
        room.setVideo("/media/" + fileName, title);
        broadcast(room, stateMessage(room, "VIDEO", p));
        broadcast(room, msg("SYSTEM", "text", p.getName() + " загрузил(а) файл: " + title));
    }


    @Scheduled(fixedDelayString = "#{${app.sync.heartbeat-seconds:5} * 1000}")
    public void heartbeat() {
        for (Room room : rooms.values()) {
            if (room.state().playing()) {
                broadcast(room, stateMessage(room, "HEARTBEAT", null));
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupEmptyRooms() {
        long ttl = props.room().emptyTtlMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        rooms.values().removeIf(room -> {
            boolean abandoned = room.isAbandoned(ttl, now);
            if (abandoned) {
                log.info("Комната {} удалена (пустая)", room.getCode());
            }
            return abandoned;
        });
    }

    private void requireControl(Room room, Participant p) {
        if (props.room().guestControl() || room.isHost(p.getId())) {
            return;
        }
        // откатываем плеер гостя к состоянию комнаты
        p.send(json(stateMessage(room, "SYNC", null)));
        throw new RoomException("Управлять видео может только хост комнаты.");
    }

    private static void requireVideo(Room room) {
        if (!room.hasVideo()) {
            throw new RoomException("Сначала загрузите видео.");
        }
    }

    private Map<String, Object> stateMessage(Room room, String reason, Participant by) {
        return msg("STATE",
                "reason", reason,
                "by", by == null ? null : by.getName(),
                "byId", by == null ? null : by.getId(),
                "state", room.state());
    }

    private void broadcastParticipants(Room room) {
        broadcast(room, msg("PARTICIPANTS", "participants", room.participantViews(), "hostId", room.getHostId()));
    }

    private void broadcast(Room room, Map<String, Object> message) {
        String json = json(message);
        for (Participant p : room.participants()) {
            p.send(json);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> msg(String type, Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static double position(JsonNode message) {
        double value = message.path("position").asDouble(0);
        return Double.isFinite(value) && value > 0 ? value : 0;
    }

    private String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        int min = props.user().minNameLength();
        int max = props.user().maxNameLength();
        if (name.length() < min) {
            throw new RoomException("Имя слишком короткое. Минимальная длина — " + min + ".");
        }
        if (name.length() > max) {
            throw new RoomException("Имя слишком длинное. Максимальная длина — " + max + ".");
        }
        return name;
    }

    private String normalizeCode(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < props.room().codeLength(); i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
