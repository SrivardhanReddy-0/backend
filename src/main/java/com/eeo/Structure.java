package com.eeo;

import java.util.*;
import java.util.regex.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

public class Structure {}

/** Turns text written by an admin into buildings, floors and rooms (and back). */
@Service class StructureService {
  static final int X0 = 201, Y0 = 96, PITCH = 150, COLS = 4;   // grid cell (col,row) -> pixel position on the 960x600 floor
  static final Pattern SEATS1 = Pattern.compile("(\\d+)\\s*seats?"), SEATS2 = Pattern.compile("seats?\\s*(\\d+)"), KW = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*kw"),
    WATT = Pattern.compile("(\\d+)\\s*w(?:atts?)?"), AT = Pattern.compile("at\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
  static class P { String name, type = "Classroom", ap, building, floor; int capacity = 30, watts = 1000; boolean maintenance, explicit; Integer col, row; }

  RoomRepo rooms; StructureService(RoomRepo r) { rooms = r; }

  static String after(String line, String kw) { return line.substring(kw.length()).replaceFirst("^[\\s:=\\-]+", "").trim(); }
  static String type(String t) {
    if (t.startsWith("lab")) return "Laboratory";
    if (t.startsWith("office") || t.startsWith("admin") || t.startsWith("staff")) return "Office";
    if (t.startsWith("class") || t.startsWith("hall") || t.startsWith("seminar") || t.startsWith("lecture")) return "Classroom";
    return Character.toUpperCase(t.charAt(0)) + t.substring(1);
  }

  Map<String, Object> importText(String text, boolean replace) {
    List<String> warnings = new ArrayList<>(); List<P> list = new ArrayList<>();
    String building = "Main Block", floor = "Ground";
    for (String raw : text.replace("\uFEFF", "").split("\\R")) {
      String line = raw.trim(), low = line.toLowerCase();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (low.startsWith("building")) { building = after(line, "building"); if (building.isEmpty()) { building = "Main Block"; warnings.add("Building line without a name: " + line); } continue; }
      if (low.startsWith("floor")) { floor = after(line, "floor"); if (floor.isEmpty()) { floor = "Ground"; warnings.add("Floor line without a name: " + line); } continue; }
      if (!low.startsWith("room")) { warnings.add("Ignored line: " + line); continue; }
      String rest = line.substring(4).trim(); int i = -1;
      for (int k = 0; k < rest.length(); k++) if (":|".indexOf(rest.charAt(k)) >= 0) { i = k; break; }
      P p = new P(); p.name = (i < 0 ? rest : rest.substring(0, i)).trim(); p.building = building; p.floor = floor;
      if (p.name.isEmpty()) { warnings.add("Room without a name: " + line); continue; }
      if (i >= 0) for (String t : rest.substring(i + 1).split("[,;|](?![^(]*\\))")) {
        t = t.trim(); String tl = t.toLowerCase(); Matcher m;
        try {
          if ((m = AT.matcher(tl)).find()) { p.col = Integer.parseInt(m.group(1)); p.row = Integer.parseInt(m.group(2)); p.explicit = true; }
          else if (tl.matches("(ap|wifi)\\b.*")) p.ap = t.replaceFirst("(?i)^(ap|wifi)[\\s:=]*", "").trim();
          else if ((m = SEATS1.matcher(tl)).find() || (m = SEATS2.matcher(tl)).find()) p.capacity = Integer.parseInt(m.group(1));
          else if ((m = KW.matcher(tl)).find()) p.watts = (int) Math.round(Double.parseDouble(m.group(1)) * 1000);
          else if ((m = WATT.matcher(tl)).find()) p.watts = Integer.parseInt(m.group(1));
          else if (tl.startsWith("maint")) p.maintenance = true;
          else if (!tl.isEmpty()) p.type = type(tl);
        } catch (RuntimeException x) { warnings.add("Could not read '" + t + "' in: " + p.name); }
      }
      list.add(p);
    }
    if (list.isEmpty()) { warnings.add("No rooms found. Nothing was changed."); return Map.of("created", 0, "updated", 0, "removed", 0, "warnings", warnings); }
    // free cells for rooms without an explicit position
    Map<String, Set<String>> taken = new HashMap<>();
    for (P p : list) if (p.explicit) taken.computeIfAbsent(p.building + "|" + p.floor, k -> new HashSet<>()).add(p.col + "," + p.row);
    for (P p : list) if (!p.explicit) {
      Set<String> cells = taken.computeIfAbsent(p.building + "|" + p.floor, k -> new HashSet<>());
      outer: for (int r = 0; r < 50; r++) for (int c = 0; c < COLS; c++) if (cells.add(c + "," + r)) { p.col = c; p.row = r; break outer; }
    }
    Map<String, Room> existing = new HashMap<>(); rooms.findAll().forEach(r -> existing.put(r.name.trim().toLowerCase(), r));
    int created = 0, updated = 0, removed = 0; Set<Long> keep = new HashSet<>();
    for (P p : list) {
      Room room = existing.get(p.name.toLowerCase()); boolean isNew = room == null; if (isNew) room = new Room();
      room.name = p.name; room.type = p.type; room.building = p.building; room.floor = p.floor; room.capacity = p.capacity; room.watts = p.watts; room.maintenance = p.maintenance; room.apName = p.ap;
      if (isNew || p.explicit || room.x == null) { room.x = (double) (X0 + p.col * PITCH); room.y = (double) (Y0 + p.row * PITCH); }
      rooms.save(room); keep.add(room.id); if (isNew) created++; else updated++;
    }
    if (replace) for (Room room : rooms.findAll()) if (!keep.contains(room.id)) { rooms.delete(room); removed++; }
    return Map.of("created", created, "updated", updated, "removed", removed, "warnings", warnings);
  }

  String export() {
    StringBuilder sb = new StringBuilder("# Edit this text, then press Apply\n");
    Map<String, Map<String, List<Room>>> by = new LinkedHashMap<>();
    for (Room r : rooms.findAll()) by.computeIfAbsent(String.valueOf(r.building), k -> new LinkedHashMap<>()).computeIfAbsent(String.valueOf(r.floor), k -> new ArrayList<>()).add(r);
    by.forEach((b, fl) -> { sb.append("building ").append(b).append('\n'); fl.forEach((f, rs) -> { sb.append("floor ").append(f).append('\n');
      for (Room r : rs) {
        sb.append("room ").append(r.name).append(": ").append(String.valueOf(r.type).toLowerCase()).append(", ").append(r.capacity).append(" seats, ").append(r.watts).append(" W");
        if (r.apName != null && !r.apName.isBlank()) sb.append(", ap ").append(r.apName);
        if (r.maintenance) sb.append(", maintenance");
        if (r.x != null && r.y != null) sb.append(", at (").append(Math.max(0, Math.round((r.x - X0) / PITCH))).append(",").append(Math.max(0, Math.round((r.y - Y0) / PITCH))).append(")");
        sb.append('\n');
      } }); });
    return sb.toString();
  }
}

@RestController @RequestMapping("/api/structure") class StructureController {
  StructureService svc; StructureController(StructureService s) { svc = s; }
  @PostMapping("/import") ResponseEntity<?> importText(@RequestBody String text, @RequestParam(defaultValue = "false") boolean replace, Authentication auth) {
    if (replace && auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) return ResponseEntity.status(403).body(Map.of("error", "Only an admin can replace all rooms"));
    return ResponseEntity.ok(svc.importText(text, replace));
  }
  @GetMapping(value = "/export", produces = "text/plain") String export() { return svc.export(); }
}
