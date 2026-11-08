package org.watchparty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("WatchParty") String name,
        @DefaultValue("") String publicUrl,
        @DefaultValue Room room,
        @DefaultValue User user,
        @DefaultValue Sync sync,
        @DefaultValue Media media,
        @DefaultValue Ytdlp ytdlp) {

    public record Room(
            @DefaultValue("6") int codeLength,
            @DefaultValue("10") int emptyTtlMinutes,
            @DefaultValue("true") boolean guestControl,
            @DefaultValue("20") int maxParticipants) {
    }

    public record User(
            @DefaultValue("3") int minNameLength,
            @DefaultValue("24") int maxNameLength) {
    }

    public record Sync(
            @DefaultValue("1.5") double driftToleranceSeconds,
            @DefaultValue("5") int heartbeatSeconds) {
    }

    public record Media(
            @DefaultValue("./media") String dir,
            @DefaultValue("24") int retentionHours) {
    }

    public record Ytdlp(
            @DefaultValue("yt-dlp") String path,
            @DefaultValue("720") int maxHeight,
            @DefaultValue("") String format,
            @DefaultValue("900") int timeoutSeconds,
            @DefaultValue("2") int maxParallelDownloads,
            @DefaultValue("0") int maxFilesizeMb,
            @DefaultValue("false") boolean allowPrivateHosts,
            @DefaultValue("") String extraArgs) {
    }
}
