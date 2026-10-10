package com.eeo;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

public class Wifi {}

/** Wi-Fi client counts per access point (AP). The collector (tools/wifi_collector.py) or the admin test button reports them. */
@RestController @RequestMapping("/api/wifi") class WifiController {
  RoomRepo rooms; LiveService live; String deviceKey; double perPerson, baseline; int staleSec;
  Map<String, long[]> seen = new ConcurrentHashMap<>();   // AP name (lower case) -> {clients, people, epochSecond}
  WifiController(RoomRepo r, LiveService l, @Value("${app.device-key}") String k, @Value("${app.wifi.devices-per-person:1.3}") double pp,
                 @Value("${app.wifi.baseline-devices:0}") double base, @Value("${app.wifi.stale-seconds:120}") int stale) {
    rooms = r; live = l; deviceKey = k; perPerson = pp; baseline = base; staleSec = stale;
  }
  int people(int clients) { return (int) Math.max(0, Math.round((clients - baseline) / perPerson)); }

  /** Body: {"ap":"AP-101","clients":37}  or  {"reports":[{"ap":"AP-101","clients":37}, ...]} with header X-Device-Key */
  @PostMapping("/report") ResponseEntity<?> report(@RequestHeader(value = "X-Device-Key", required = false) String key, @RequestBody Map<String, Object> b) {
    if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("error", "Bad or missing X-Device-Key"));
    return apply(b);
  }
  @PostMapping("/test") ResponseEntity<?> test(@RequestBody Map<String, Object> b) { return apply(b); }

  ResponseEntity<?> apply(Map<String, Object> b) {
    List<Map<String, Object>> list = new ArrayList<>();
    if (b.get("reports") instanceof List<?> l) {
      for (Object o : l) if (o instanceof Map<?, ?> m) { Map<String, Object> mm = new HashMap<>(); m.forEach((k, v) -> mm.put(String.valueOf(k), v)); list.add(mm); }
    } else list.add(b);
    int matched = 0; List<String> unknown = new ArrayList<>(); List<Room> all = rooms.findAll();
    for (Map<String, Object> r : list) {
      String ap = String.valueOf(r.get("ap")).trim(); Integer c = LiveController.num(r.get("clients"));
      if (c == null) { unknown.add(ap + " (no clients number)"); continue; }
      Room room = all.stream().filter(x -> x.apName != null && x.apName.equalsIgnoreCase(ap)).findFirst().orElse(null);
      if (room == null) { unknown.add(ap); continue; }
      int p = people(c); seen.put(ap.toLowerCase(), new long[]{c, p, Instant.now().getEpochSecond()});
      live.apply(room.id, p, null, null); matched++;
    }
    if (matched > 0) live.publish();
    return ResponseEntity.ok(Map.of("matched", matched, "unknownAps", unknown));
  }

  @GetMapping("/status") Map<String, Object> status() {
    long now = Instant.now().getEpochSecond(); List<Map<String, Object>> out = new ArrayList<>();
    for (Room r : rooms.findAll()) {
      if (r.apName == null || r.apName.isBlank()) continue;
      long[] s = seen.get(r.apName.toLowerCase());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("room", r.name); m.put("ap", r.apName);
      m.put("clients", s == null ? null : s[0]); m.put("people", s == null ? null : s[1]); m.put("secondsAgo", s == null ? null : now - s[2]);
      m.put("state", s == null ? "none" : now - s[2] > staleSec ? "stale" : "live");
      out.add(m);
    }
    return Map.of("aps", out, "devicesPerPerson", perPerson, "baselineDevices", baseline, "staleSeconds", staleSec);
  }
}
