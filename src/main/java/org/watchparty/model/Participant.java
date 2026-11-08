package org.watchparty.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Slf4j
public class Participant {


    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String token = UUID.randomUUID().toString();
    private final String name;
    private final WebSocketSession session;
    private final String roomCode;

    public Participant(String name, WebSocketSession session, String roomCode) {
        this.name = name;
        this.session = session;
        this.roomCode = roomCode;
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getName() {
        return name;
    }

    public WebSocketSession getSession() {
        return session;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void send(String json) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("Не удалось отправить сообщение участнику {}: {}", name, e.getMessage());
        }
    }
}
