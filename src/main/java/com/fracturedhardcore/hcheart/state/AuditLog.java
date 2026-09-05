package com.fracturedhardcore.hcheart.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

import com.fracturedhardcore.hcheart.HcHeart;

/** Append-only text log of every state change: timestamp, uuid, name, event, details. */
public final class AuditLog {
	private final Path file;

	public AuditLog(Path file) { this.file = file; }

	public synchronized void log(UUID id, String name, String event, String details) {
		String who = name == null || name.isEmpty() ? "?" : name;
		String line = Instant.now() + " " + id + " " + who + " " + event + " " + details + System.lineSeparator();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			HcHeart.LOGGER.error("Could not write audit log {}", file, e);
		}
		HcHeart.LOGGER.info("[audit] {} ({}) {} {}", who, id, event, details);
	}
}
