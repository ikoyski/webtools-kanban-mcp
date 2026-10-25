package com.ikoyski.webtools.kanban.mcp.client;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * One method per Kanban API operation the MCP tools need. Paths are relative to
 * kanban.api.base-url (https://api.ikoyski.top/kanban-backend/v1 by default).
 *
 * Every method returns a String the model can read: sanitized text on success,
 * or a short "Error: ..." message on failure (never a stack trace).
 *
 * TODO: request body field names marked VERIFY below are best guesses; check them
 * against the backend DTOs. Spring Boot ignores unknown JSON properties by default,
 * so a wrong name shows up as a missing value or a 400, not a crash. Where the name
 * is uncertain (name vs title) both are sent; trim to the real one once confirmed.
 */
@Component
public class KanbanApiClient {

    private static final Logger log = LoggerFactory.getLogger(KanbanApiClient.class);

    // Ids are numeric or UUID-like. Rejecting anything else stops a prompt-injected agent
    // from smuggling path segments such as "../boards" into the URL.
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int MAX_LABELS = 10;
    private static final int MAX_LABEL_LENGTH = 50;
    private static final int MAX_NAME_LENGTH = 100;

    private final RestClient http;
    private final int maxChars;

    public KanbanApiClient(RestClient kanbanRestClient,
                           @Value("${kanban.mcp.max-response-chars}") int maxChars) {
        this.http = kanbanRestClient;
        this.maxChars = maxChars;
    }

    // ---- boards -----------------------------------------------------------

    public String listBoards() {
        return call(() -> http.get().uri("/boards").retrieve().body(String.class));
    }

    public String getBoard(String boardId) {
        return call(() -> http.get().uri("/boards/{id}", id(boardId)).retrieve().body(String.class));
    }

    public String createBoard(String name, String description) {
        return call(() -> {
            String boardName = cleanName(name);
            Map<String, Object> body = new HashMap<>();
            body.put("name", boardName); 
            return http.post().uri("/boards")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
        });
    }

    // ---- columns ----------------------------------------------------------

    public String createColumn(String boardId, String name) {
        return call(() -> {
            id(boardId);
            String result = postColumn(boardId, cleanName(name));
            return (result == null || result.isBlank()) ? "OK" : sanitize(result);
        });
    }

    public String renameColumn(String columnId, String name) {
        return call(() -> {
            String columnName = cleanName(name);
            Map<String, Object> body = new HashMap<>();
            body.put("title", columnName); 
            return http.patch().uri("/columns/{id}", id(columnId))
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
        });
    }

    private String postColumn(String boardId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("boardId", boardId); 
        body.put("title", name); 
        return http.post().uri("/columns")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class);
    }

    // ---- cards ------------------------------------------------------------

    public String createCard(String columnId, String title, String description,
                             String priority, String dueDate, List<String> labels) {
        return call(() -> {
            Map<String, Object> body = new HashMap<>();
            body.put("columnId", id(columnId));
            body.put("title", cleanTitle(title));
            if (description != null) {
                body.put("description", description);
            }
            putCardOptions(body, priority, dueDate, labels);
            return http.post().uri("/cards")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
        });
    }

    /**
     * Changes only the fields that are provided (the backend ignores nulls, so a due date or labels
     * cannot be cleared through this tool).
     */
    public String updateCard(String cardId, String title, String description,
                             String priority, String dueDate, List<String> labels) {
        return call(() -> {
            Map<String, Object> body = new HashMap<>();
            if (title != null) {
                body.put("title", cleanTitle(title));
            }
            if (description != null) {
                body.put("description", description);
            }
            putCardOptions(body, priority, dueDate, labels);
            if (body.isEmpty()) {
                throw new IllegalArgumentException(
                        "provide at least one field to change (title, description, priority, dueDate or labels).");
            }
            return http.patch().uri("/cards/{id}", id(cardId))
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
        });
    }

    /** The backend requires the position, so it is required here too. */
    public String moveCard(String cardId, String columnId, Integer position) {
        return call(() -> {
            if (position == null || position < 0) {
                throw new IllegalArgumentException(
                        "position is required: the zero-based index in the destination column "
                                + "(use the number of cards already in that column to put the card last).");
            }
            Map<String, Object> body = new HashMap<>();
            body.put("columnId", id(columnId));
            body.put("position", position);
            return http.patch().uri("/cards/{id}/move", id(cardId))
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(String.class);
        });
    }

    public String archiveCard(String cardId) {
        return call(() -> http.post().uri("/cards/{id}/archive", id(cardId))
                .retrieve().body(String.class));
    }

    public String restoreCard(String cardId) {
        return call(() -> http.post().uri("/cards/{id}/restore", id(cardId))
                .retrieve().body(String.class));
    }

    public String listArchivedCards(String boardId) {
        return call(() -> http.get().uri("/cards/board/{boardId}/archived", id(boardId))
                .retrieve().body(String.class));
    }

    // ---- comments ---------------------------------------------------------

    public String listComments(String cardId) {
        return call(() -> http.get().uri("/comments/card/{cardId}", id(cardId))
                .retrieve().body(String.class));
    }

    public String addComment(String cardId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("content", text); 
        return call(() -> http.post().uri("/comments/card/{cardId}", id(cardId))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class));
    }

    // ---- helpers ----------------------------------------------------------

    private static String id(String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid id. Ids come from list_boards or get_board and contain only letters, digits, - and _.");
        }
        return value;
    }

    private static String cleanTitle(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title must not be empty.");
        }
        return title;
    }

    /** Validates the optional card fields here so a mistake gives a clear message, not a backend error. */
    private static void putCardOptions(Map<String, Object> body, String priority, String dueDate, List<String> labels) {
        if (priority != null && !priority.isBlank()) {
            String p = priority.trim().toUpperCase(Locale.ROOT);
            if (!p.equals("LOW") && !p.equals("MEDIUM") && !p.equals("HIGH")) {
                throw new IllegalArgumentException("priority must be LOW, MEDIUM or HIGH.");
            }
            body.put("priority", p);
        }
        if (dueDate != null && !dueDate.isBlank()) {
            try {
                body.put("dueDate", LocalDate.parse(dueDate.trim()).toString());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("dueDate must be a date like 2026-12-31.");
            }
        }
        if (labels != null) {
            if (labels.size() > MAX_LABELS) {
                throw new IllegalArgumentException("at most " + MAX_LABELS + " labels.");
            }
            List<String> cleaned = new ArrayList<>();
            for (String label : labels) {
                String l = label == null ? "" : label.trim();
                if (l.isEmpty() || l.length() > MAX_LABEL_LENGTH) {
                    throw new IllegalArgumentException(
                            "each label must be 1 to " + MAX_LABEL_LENGTH + " characters.");
                }
                cleaned.add(l);
            }
            body.put("labels", cleaned);
        }
    }

    private static String cleanName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name is too long (max " + MAX_NAME_LENGTH + " characters).");
        }
        return name;
    }

    private String call(Supplier<String> request) {
        try {
            String body = request.get();
            return (body == null || body.isBlank()) ? "OK" : sanitize(body);
        } catch (RuntimeException e) {
            return errorMessage(e);
        }
    }

    private String errorMessage(RuntimeException e) {
        if (e instanceof IllegalArgumentException) {
            return "Error: " + e.getMessage();
        }
        if (e instanceof RestClientResponseException r) {
            int s = r.getStatusCode().value();
            log.debug("Kanban API returned HTTP {}", s);
            return switch (s) {
                case 400, 422 -> "Error: the API rejected the request (HTTP " + s + "): "
                        + sanitize(truncate(r.getResponseBodyAsString(), 300));
                case 401 -> "Error: not authenticated (token missing, invalid or expired).";
                case 403 -> "Error: you do not have access to that resource.";
                case 404 -> "Error: not found.";
                case 429 -> "Error: rate limited, try again shortly.";
                default -> "Error: the Kanban API returned HTTP " + s + ".";
            };
        }
        if (e instanceof ResourceAccessException) {
            return "Error: could not reach the Kanban API (timeout or connection failure).";
        }
        log.warn("Unexpected failure calling the Kanban API", e);
        return "Error: unexpected failure.";
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }

    /**
     * Card titles, descriptions and comments are user-written, and the UI stores rich HTML.
     * Treat them as untrusted: turn paragraph breaks into newlines (kept as the JSON escape
     * so the payload stays valid), strip tags, and cap the total size before the text reaches
     * the model. This reduces, but does not eliminate, prompt-injection risk.
     */
    private String sanitize(String raw) {
        String text = raw
                .replaceAll("(?i)</p>|<br\\s*/?>", "\\\\n")
                .replaceAll("</?[a-zA-Z][^>]*>", "");
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "... [truncated]";
        }
        return text;
    }
}
