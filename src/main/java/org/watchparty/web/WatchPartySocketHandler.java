package org.watchparty.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.watchparty.model.RoomException;
import org.watchparty.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WatchPartySocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WatchPartySocketHandler.class);

    private final RoomService rooms;
    private final ObjectMapper mapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WatchPartySocketHandler(RoomService rooms, ObjectMapper mapper) {
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) {
        WebSocketSession session = sessions.getOrDefault(rawSession.getId(), rawSession);
        String type = "";
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            type = node.path("type").asText("");
            if ("JOIN".equals(type)) {
                rooms.join(session, node.path("code").asText(""), node.path("username").asText(""));
            } else {
                rooms.handle(rawSession.getId(), node);
            }
        } catch (RoomException e) {
            sendError(session, "JOIN".equals(type) ? "JOIN_FAILED" : "ERROR", e.getMessage());
        } catch (JsonProcessingException e) {
            sendError(session, "ERROR", "Некорректное сообщение.");
        } catch (Exception e) {
            log.error("Ошибка обработки сообщения", e);
            sendError(session, "ERROR", "Внутренняя ошибка сервера.");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        rooms.leave(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        log.debug("Ошибка транспорта WebSocket: {}", exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }

    private void sendError(WebSocketSession session, String type, String text) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type", type, "message", text))));
        } catch (IOException e) {
            log.debug("Не удалось отправить ошибку клиенту: {}", e.getMessage());
        }
    }
}
