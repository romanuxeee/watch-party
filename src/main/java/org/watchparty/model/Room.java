package org.watchparty.model;

import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Room {

    private static final int HISTORY_LIMIT = 50;

    public record StateView(String videoUrl, String title, double position, boolean playing) {
    }

    public record ParticipantView(String id, String name, boolean host) {
    }

    @Getter
    private final String code;
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final Deque<ChatMessage> history = new ArrayDeque<>();

    private String hostId;

    private String videoUrl;
    private String title = "";
    private double basePosition;
    private boolean playing;
    private long baseTime = System.currentTimeMillis();

    private boolean loading;
    private long emptySince = System.currentTimeMillis();

    public Room(String code) {
        this.code = code;
    }

    public synchronized Participant addParticipant(String name, WebSocketSession session, int max) {
        if (participants.size() >= max) {
            throw new RoomException("Комната заполнена: максимум участников — " + max + ".");
        }
        Participant p = new Participant(uniqueName(name), session, code);
        participants.put(p.getId(), p);
        if (hostId == null) {
            hostId = p.getId();
        }
        return p;
    }

    public synchronized Participant removeParticipant(String id) {
        Participant removed = participants.remove(id);
        if (removed == null) {
            return null;
        }
        if (participants.isEmpty()) {
            hostId = null;
            emptySince = System.currentTimeMillis();
            return null;
        }
        if (id.equals(hostId)) {
            Participant next = participants.values().iterator().next();
            hostId = next.getId();
            return next;
        }
        return null;
    }

    public synchronized List<Participant> participants() {
        return new ArrayList<>(participants.values());
    }

    public synchronized List<ParticipantView> participantViews() {
        List<ParticipantView> list = new ArrayList<>();
        for (Participant p : participants.values()) {
            list.add(new ParticipantView(p.getId(), p.getName(), p.getId().equals(hostId)));
        }
        return list;
    }

    public synchronized boolean isHost(String participantId) {
        return participantId != null && participantId.equals(hostId);
    }

    public synchronized String getHostId() {
        return hostId;
    }

    public synchronized int size() {
        return participants.size();
    }

    public synchronized boolean isEmpty() {
        return participants.isEmpty();
    }

    public synchronized boolean isAbandoned(long ttlMillis, long now) {
        return participants.isEmpty() && !loading && now - emptySince > ttlMillis;
    }

    private String uniqueName(String name) {
        String candidate = name;
        int n = 2;
        while (nameTaken(candidate)) {
            candidate = name + " (" + n++ + ")";
        }
        return candidate;
    }

    private boolean nameTaken(String name) {
        for (Participant p : participants.values()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void addChat(ChatMessage message) {
        history.addLast(message);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    public synchronized List<ChatMessage> history() {
        return new ArrayList<>(history);
    }

    public synchronized boolean hasVideo() {
        return videoUrl != null;
    }

    public synchronized void setVideo(String url, String videoTitle) {
        this.videoUrl = url;
        this.title = videoTitle == null ? "" : videoTitle;
        this.basePosition = 0;
        this.playing = false;
        this.baseTime = System.currentTimeMillis();
    }

    public synchronized void play(double position) {
        this.basePosition = position;
        this.playing = true;
        this.baseTime = System.currentTimeMillis();
    }

    public synchronized void pause(double position) {
        this.basePosition = position;
        this.playing = false;
        this.baseTime = System.currentTimeMillis();
    }

    public synchronized void seek(double position) {
        this.basePosition = position;
        this.baseTime = System.currentTimeMillis();
    }

    public synchronized StateView state() {
        return new StateView(videoUrl, title, currentPosition(), playing && videoUrl != null);
    }

    private double currentPosition() {
        if (playing) {
            return basePosition + (System.currentTimeMillis() - baseTime) / 1000.0;
        }
        return basePosition;
    }

    public synchronized boolean tryStartLoading() {
        if (loading) {
            return false;
        }
        loading = true;
        return true;
    }

    public synchronized void finishLoading() {
        loading = false;
    }
}
