package com.eeo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

public class Chat {}

/** Chatbot connection edited by the admin. The API key stays on the server and is never sent to the browser. */
@Entity class ChatConfig {
  @Id public Long id; @Column(name = "api_format") public String format; @Column(name = "base_url") public String baseUrl; public String model;
  @Column(name = "api_key", length = 600) public String apiKey; @Column(name = "system_prompt", length = 4000) public String systemPrompt; public Boolean enabled;
}
interface ChatConfigRepo extends JpaRepository<ChatConfig, Long> {}

@RestController @RequestMapping("/api/chat") class ChatController {
  static final String PROMPT = "You are the Energy Assistant for a university building energy dashboard. Answer only from the live data below. Be concise (under 80 words). Use room names exactly as written. If the data does not contain the answer, say so.";
  ChatConfigRepo repo; Optimizer opt; ObjectMapper om = new ObjectMapper(); HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  ChatController(ChatConfigRepo r, Optimizer o) { repo = r; opt = o; }

  ChatConfig cfg() { return repo.findById(1L).orElseGet(() -> { ChatConfig c = new ChatConfig(); c.id = 1L; c.format = "openai"; c.systemPrompt = PROMPT; c.enabled = false; return c; }); }

  @GetMapping("/settings") Map<String, Object> settings() {
    ChatConfig c = cfg(); Map<String, Object> m = new LinkedHashMap<>();
    m.put("format", c.format == null ? "openai" : c.format); m.put("baseUrl", c.baseUrl == null ? "" : c.baseUrl); m.put("model", c.model == null ? "" : c.model);
    m.put("systemPrompt", c.systemPrompt == null || c.systemPrompt.isBlank() ? PROMPT : c.systemPrompt);
    m.put("enabled", Boolean.TRUE.equals(c.enabled)); m.put("hasKey", c.apiKey != null && !c.apiKey.isBlank());
    return m;
  }
  @PutMapping("/settings") Map<String, Object> save(@RequestBody Map<String, Object> b) {
    ChatConfig c = cfg();
    if (b.get("format") != null) c.format = String.valueOf(b.get("format"));
    if (b.get("baseUrl") != null) c.baseUrl = String.valueOf(b.get("baseUrl")).trim();
    if (b.get("model") != null) c.model = String.valueOf(b.get("model")).trim();
    if (b.get("systemPrompt") != null) c.systemPrompt = String.valueOf(b.get("systemPrompt"));
    if (b.get("enabled") != null) c.enabled = Boolean.TRUE.equals(b.get("enabled"));
    String key = b.get("apiKey") == null ? "" : String.valueOf(b.get("apiKey")).trim();
    if (!key.isEmpty()) c.apiKey = key;           // blank = keep the saved key
    repo.save(c); return Map.of("ok", true);
  }
  @GetMapping("/status") Map<String, Object> status() { ChatConfig c = cfg(); return Map.of("enabled", Boolean.TRUE.equals(c.enabled) && c.model != null && !c.model.isBlank()); }

  @PostMapping("/ask") ResponseEntity<?> ask(@RequestBody Map<String, Object> b) { return reply(b, false); }
  @PostMapping("/test") ResponseEntity<?> test() { return reply(Map.of("message", "Give a one-sentence status summary of the campus."), true); }

  @SuppressWarnings("unchecked")
  ResponseEntity<?> reply(Map<String, Object> b, boolean force) {
    ChatConfig c = cfg();
    if (!force && !Boolean.TRUE.equals(c.enabled)) return ResponseEntity.ok(Map.of("source", "rules"));
    if (c.model == null || c.model.isBlank()) return ResponseEntity.ok(Map.of("source", "rules", "error", "No model name is set"));
    try {
      List<Map<String, String>> msgs = new ArrayList<>();
      if (b.get("history") instanceof List<?> h) for (Object o : h) if (o instanceof Map<?, ?> m) msgs.add(Map.of("role", "assistant".equals(String.valueOf(m.get("role"))) ? "assistant" : "user", "content", String.valueOf(m.get("content"))));
      if (msgs.size() > 10) msgs = new ArrayList<>(msgs.subList(msgs.size() - 10, msgs.size()));
      while (!msgs.isEmpty() && "assistant".equals(msgs.get(0).get("role"))) msgs.remove(0);
      if (msgs.isEmpty()) msgs.add(Map.of("role", "user", "content", String.valueOf(b.get("message"))));

      Map<String, Object> data = opt.run(); List<Map<String, Object>> rooms = new ArrayList<>();
      for (Map<String, Object> r : (List<Map<String, Object>>) data.get("rooms"))
        rooms.add(Map.of("room", String.valueOf(r.get("name")), "floor", r.get("building") + " / " + r.get("floor"), "status", String.valueOf(r.get("status")),
          "utilizationPercent", r.get("utilization"), "wastedKwh", r.get("wasteKwh"), "seats", r.get("capacity")));
      String json = om.writeValueAsString(Map.of("totals", data.get("totals"), "recommendations", ((List<Object>) data.get("recommendations")).stream().limit(8).toList(), "rooms", rooms));
      String system = (c.systemPrompt == null || c.systemPrompt.isBlank() ? PROMPT : c.systemPrompt) + "\n\nLIVE DATA (JSON):\n" + json;
      String text = call(c, system, msgs);
      if (text == null || text.isBlank()) throw new RuntimeException("The model returned an empty reply");
      return ResponseEntity.ok(Map.of("reply", text.trim(), "source", "llm"));
    } catch (Exception e) {
      String m = String.valueOf(e.getMessage()); return ResponseEntity.ok(Map.of("source", "rules", "error", m.length() > 300 ? m.substring(0, 300) : m));
    }
  }

  String call(ChatConfig c, String system, List<Map<String, String>> msgs) throws Exception {
    boolean anthropic = "anthropic".equals(c.format);
    String base = c.baseUrl == null || c.baseUrl.isBlank() ? (anthropic ? "https://api.anthropic.com" : "https://api.openai.com/v1") : c.baseUrl.trim().replaceAll("/+$", "");
    HttpRequest.Builder rb; String body;
    if (anthropic) {
      Map<String, Object> m = Map.of("model", c.model, "max_tokens", 600, "system", system, "messages", msgs);
      body = om.writeValueAsString(m);
      rb = HttpRequest.newBuilder(URI.create(base.replaceAll("/v1$", "") + "/v1/messages")).header("x-api-key", String.valueOf(c.apiKey)).header("anthropic-version", "2023-06-01");
    } else {
      List<Map<String, String>> all = new ArrayList<>(); all.add(Map.of("role", "system", "content", system)); all.addAll(msgs);
      Map<String, Object> m = Map.of("model", c.model, "messages", all);
      body = om.writeValueAsString(m);
      rb = HttpRequest.newBuilder(URI.create(base + "/chat/completions"));
      if (c.apiKey != null && !c.apiKey.isBlank()) rb.header("authorization", "Bearer " + c.apiKey);
    }
    HttpResponse<String> res = http.send(rb.timeout(Duration.ofSeconds(40)).header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() / 100 != 2) throw new RuntimeException("The AI service answered HTTP " + res.statusCode() + ": " + res.body());
    JsonNode n = om.readTree(res.body());
    return anthropic ? n.path("content").path(0).path("text").asText("") : n.path("choices").path(0).path("message").path("content").asText("");
  }
}
