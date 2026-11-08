package org.watchparty.web;

import org.watchparty.config.AppProperties;
import org.watchparty.model.Participant;
import org.watchparty.model.Room;
import org.watchparty.service.MediaStorage;
import org.watchparty.service.RoomService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final AppProperties props;
    private final RoomService rooms;
    private final MediaStorage storage;

    public ApiController(AppProperties props, RoomService rooms, MediaStorage storage) {
        this.props = props;
        this.rooms = rooms;
        this.storage = storage;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appName", props.name());
        m.put("publicUrl", props.publicUrl());
        m.put("minNameLength", props.user().minNameLength());
        m.put("maxNameLength", props.user().maxNameLength());
        m.put("codeLength", props.room().codeLength());
        m.put("driftToleranceSeconds", props.sync().driftToleranceSeconds());
        m.put("guestControl", props.room().guestControl());
        return m;
    }

    @PostMapping("/rooms")
    public Map<String, String> createRoom() {
        Room room = rooms.createRoom();
        return Map.of("code", room.getCode());
    }

    @GetMapping("/rooms/{code}")
    public Map<String, Object> roomInfo(@PathVariable String code) {
        Room room = rooms.getRoom(code);
        return Map.of("code", room.getCode(), "participants", room.size());
    }

    @PostMapping(value = "/rooms/{code}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String code,
                                      @RequestParam("token") String token,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        Participant participant = rooms.authorizeUpload(code, token);
        String storedName = storage.saveUpload(file);
        rooms.attachUploadedVideo(participant, storedName, displayName(file.getOriginalFilename()));
        return Map.of("ok", true);
    }

    private static String displayName(String original) {
        if (original == null || original.isBlank()) {
            return "Видео";
        }
        try {
            return Paths.get(original).getFileName().toString();
        } catch (Exception e) {
            return "Видео";
        }
    }
}
