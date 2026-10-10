package com.eeo;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

public class Campus {}

/** One blueprint per "building / floor" key. Upload saves a draft; "deploy" makes its hologram appear on the dashboard. */
@RestController @RequestMapping("/api/plans") class PlanController {
  PlanRepo plans; PlanController(PlanRepo p) { plans = p; }
  @GetMapping Map<String, Object> get(@RequestParam String key) {
    return plans.findById(key).map(p -> { Map<String, Object> m = new HashMap<>(); m.put("image", p.image); m.put("holo", p.holo); m.put("slab", p.slab); m.put("deployed", Boolean.TRUE.equals(p.deployed)); return m; }).orElse(new HashMap<>());
  }
  @PutMapping Map<String, Object> put(@RequestParam String key, @RequestBody Map<String, String> b) {
    Plan p = plans.findById(key).orElseGet(Plan::new); p.id = key; p.image = b.get("image"); p.holo = b.get("holo"); p.slab = b.get("slab"); p.deployed = false; plans.save(p); return Map.of("ok", true);
  }
  @PostMapping("/deploy") Map<String, Object> deploy(@RequestParam String key) { plans.findById(key).ifPresent(p -> { p.deployed = true; plans.save(p); }); return Map.of("ok", true); }
  @PostMapping("/undeploy") Map<String, Object> undeploy(@RequestParam String key) { plans.findById(key).ifPresent(p -> { p.deployed = false; plans.save(p); }); return Map.of("ok", true); }
  @DeleteMapping void delete(@RequestParam String key) { plans.deleteById(key); }
}

/** CSV columns: room,day,start,end,course,students   (day = Mon..Sun, 1..7 or daily; times like 09:00) */
@RestController @RequestMapping("/api/timetable") class TimetableController {
  RoomRepo rooms; BookingRepo bookings; TimetableController(RoomRepo r, BookingRepo b) { rooms = r; bookings = b; }

  @PostMapping("/import") ResponseEntity<?> importCsv(@RequestBody String csv) {
    Map<String, Room> byName = new HashMap<>(); rooms.findAll().forEach(r -> byName.put(r.name.trim().toLowerCase(), r));
    List<Booking> out = new ArrayList<>(); Set<String> unknown = new TreeSet<>(); int bad = 0;
    String[] lines = csv.replace("\uFEFF", "").split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim(); if (line.isEmpty()) continue;
      String[] c = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
      for (int k = 0; k < c.length; k++) c[k] = c[k].trim().replaceAll("^\"|\"$", "");
      if (i == 0 && c[0].equalsIgnoreCase("room")) continue;
      try {
        Room r = byName.get(c[0].toLowerCase());
        if (r == null) { unknown.add(c[0]); continue; }
        Booking b = new Booking(); b.roomId = r.id; b.day = day(c[1]); b.startHour = hour(c[2], false); b.endHour = hour(c[3], true);
        if (b.endHour <= b.startHour) { bad++; continue; }
        b.course = c.length > 4 ? c[4] : ""; b.expected = c.length > 5 && !c[5].isEmpty() ? Integer.parseInt(c[5]) : 0;
        out.add(b);
      } catch (RuntimeException x) { bad++; }
    }
    if (out.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No valid rows. Columns needed: room,day,start,end,course,students. Room names must match exactly.", "unknownRooms", unknown, "badRows", bad));
    bookings.deleteAll(); bookings.saveAll(out);
    return ResponseEntity.ok(Map.of("imported", out.size(), "unknownRooms", unknown, "badRows", bad));
  }

  static int day(String s) {
    s = s.trim().toLowerCase();
    if (s.isEmpty() || s.startsWith("dai") || s.startsWith("all") || s.startsWith("every")) return 0;
    String[] n = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
    for (int i = 0; i < 7; i++) if (s.startsWith(n[i])) return i + 1;
    int v = Integer.parseInt(s); if (v < 0 || v > 7) throw new IllegalArgumentException(); return v;
  }
  static int hour(String s, boolean roundUp) {
    String[] p = s.trim().split(":"); int h = Integer.parseInt(p[0].trim());
    int m = p.length > 1 ? Integer.parseInt(p[1].trim().replaceAll("\\D.*", "")) : 0;
    if (h < 0 || h > 24) throw new IllegalArgumentException();
    return roundUp && m > 0 ? h + 1 : h;
  }
}
