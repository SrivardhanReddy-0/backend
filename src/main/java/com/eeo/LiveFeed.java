package com.eeo;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class LiveFeed {}

/** Keeps the open dashboard connections and pushes a fresh analytics snapshot to all of them. */
@Component class LiveHub {
  List<SseEmitter> clients = new CopyOnWriteArrayList<>();
  SseEmitter join() {
    SseEmitter e = new SseEmitter(0L); clients.add(e);
    e.onCompletion(() -> clients.remove(e)); e.onTimeout(() -> clients.remove(e)); e.onError(x -> clients.remove(e));
    return e;
  }
  void send(SseEmitter e, Object data) {
    try { e.send(SseEmitter.event().name("analytics").data(data, MediaType.APPLICATION_JSON)); } catch (Exception x) { clients.remove(e); }
  }
  void push(Object data) { clients.forEach(e -> send(e, data)); }
}

@org.springframework.stereotype.Service class LiveService {
  ReadingRepo readings; LiveHub hub; Optimizer opt;
  LiveService(ReadingRepo r, LiveHub h, Optimizer o) { readings = r; hub = h; opt = o; }
  /** Writes the room's reading for the current hour (insert or overwrite). */
  void apply(Long roomId, Integer headcount, Boolean equipmentOn, Integer watts) {
    int h = Optimizer.liveHour(); LocalDate d = LocalDate.now();
    Reading r = readings.findByRoomIdAndHourAndDate(roomId, h, d).orElseGet(() -> { Reading n = new Reading(); n.roomId = roomId; n.hour = h; n.date = d; return n; });
    if (headcount != null) r.headcount = Math.max(0, headcount);          // only fields that were sent are changed
    if (watts != null) { r.watts = watts; if (equipmentOn == null) r.equipmentOn = watts > 20; }
    if (equipmentOn != null) r.equipmentOn = equipmentOn;
    readings.save(r);
  }
  void publish() { if (!hub.clients.isEmpty()) hub.push(opt.run()); }
}

/** Demo sensors: every few seconds some rooms change occupancy, exactly as a real device would report. */
@Component class Simulator {
  RoomRepo rooms; LiveService live; volatile boolean on; Random rnd = new Random();
  Simulator(RoomRepo r, LiveService l, @Value("${app.simulator:true}") boolean on) { rooms = r; live = l; this.on = on; }
  @Scheduled(fixedDelay = 4000) void tick() {
    if (!on) return;
    for (Room r : rooms.findAll()) {
      if (r.maintenance || rnd.nextDouble() > 0.4) continue;
      int hc = rnd.nextDouble() < 0.3 ? 0 : (int) (r.capacity * (0.1 + rnd.nextDouble() * 0.85));
      live.apply(r.id, hc, hc > 0 || rnd.nextDouble() < 0.5, null);
    }
    live.publish();
  }
}

@RestController @RequestMapping("/api/live") class LiveController {
  LiveHub hub; LiveService live; Simulator sim; Optimizer opt; RoomRepo rooms; String deviceKey;
  LiveController(LiveHub h, LiveService l, Simulator s, Optimizer o, RoomRepo r, @Value("${app.device-key}") String k) { hub = h; live = l; sim = s; opt = o; rooms = r; deviceKey = k; }

  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter stream() {
    SseEmitter e = hub.join(); hub.send(e, opt.run()); return e;
  }
  /** Real devices call this with header X-Device-Key. Body: {"roomId":1,"headcount":12,"equipmentOn":true} */
  @PostMapping("/readings") ResponseEntity<?> ingest(@RequestHeader(value = "X-Device-Key", required = false) String key, @RequestBody Map<String, Object> b) {
    if (!deviceKey.equals(key)) return ResponseEntity.status(401).body(Map.of("error", "Bad or missing X-Device-Key"));
    try {
      Long id = roomId(b);
      if (id == null || !rooms.existsById(id)) return ResponseEntity.status(404).body(Map.of("error", "Unknown room"));
      live.apply(id, num(b.get("headcount")), b.get("equipmentOn") instanceof Boolean e ? e : null, num(b.get("watts")));
      live.publish();
      return ResponseEntity.ok(Map.of("ok", true));
    } catch (RuntimeException x) { return ResponseEntity.badRequest().body(Map.of("error", "Send roomId or roomName, plus headcount, equipmentOn and/or watts")); }
  }
  Long roomId(Map<String, Object> b) {
    if (b.get("roomId") instanceof Number n) return n.longValue();
    String name = String.valueOf(b.get("roomName"));
    return rooms.findAll().stream().filter(r -> r.name.equalsIgnoreCase(name)).map(r -> r.id).findFirst().orElse(null);
  }
  static Integer num(Object o) { return o instanceof Number n ? Integer.valueOf(n.intValue()) : null; }
  @GetMapping("/simulator") Map<String, Boolean> simState() { return Map.of("on", sim.on); }
  @PostMapping("/simulator") Map<String, Boolean> simSet(@RequestParam boolean on) { sim.on = on; return Map.of("on", on); }
}
