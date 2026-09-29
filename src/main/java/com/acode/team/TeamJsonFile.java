package com.acode.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared whole-file JSON protocol. Callers hold TeamFileLock during read/modify/write. */
final class TeamJsonFile {
    static final ObjectMapper JSON = new ObjectMapper();
    final Path path;

    TeamJsonFile(Path path) { this.path = path.toAbsolutePath().normalize(); }

    TeamFileLock lock() throws IOException {
        TeamFileLock.rejectLink(path);
        return TeamFileLock.acquire(path.resolveSibling(path.getFileName() + ".lock"));
    }

    JsonNode read() throws IOException {
        TeamFileLock.rejectLink(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return JSON.createArrayNode();
        JsonNode node = JSON.readTree(Files.readString(path));
        if (node == null || !node.isArray()) throw new IOException("预期 JSON 数组：" + path.getFileName());
        return node;
    }

    void write(JsonNode node) throws IOException {
        TeamFileLock.rejectLink(path);
        Path temporary = Files.createTempFile(path.getParent(), ".team-write-", ".tmp");
        try {
            Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            TeamFileLock.rejectLink(path);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String text(JsonNode node, String key) throws IOException {
        if (!node.path(key).isTextual()) throw new IOException("字段必须为字符串：" + key);
        return node.path(key).textValue();
    }
}
