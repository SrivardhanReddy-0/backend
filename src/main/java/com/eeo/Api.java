package com.eeo;

import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.springframework.web.bind.annotation.*;

public class Api {}

@RestController @RequestMapping("/api/rooms") class RoomController {
  RoomRepo rooms; RoomController(RoomRepo r) { rooms = r; }
  @PostMapping Room add(@RequestBody Room r) {
    r.id = null; r.x = null; r.y = null;
    if (r.building == null || r.building.isBlank()) r.building = "Main Block";
    if (r.floor == null || r.floor.isBlank()) r.floor = "Ground";
    return rooms.save(r);
  }
  @PutMapping("/{id}/position") void move(@PathVariable Long id, @RequestBody Map<String, Double> b) { Room r = rooms.findById(id).orElseThrow(); r.x = b.get("x"); r.y = b.get("y"); rooms.save(r); }
  @DeleteMapping("/{id}") void delete(@PathVariable Long id) { rooms.deleteById(id); }
}

@RestController @RequestMapping("/api/analytics") class Optimizer {
  RoomRepo rooms; BookingRepo bookings; ReadingRepo readings; boolean assumeOn;
  Optimizer(RoomRepo r, BookingRepo b, ReadingRepo d, @org.springframework.beans.factory.annotation.Value("${app.assume-on-when-booked:true}") boolean assumeOn) { rooms = r; bookings = b; readings = d; this.assumeOn = assumeOn; }
  static final int FROM = 8, TO = 17;
  static int liveHour() { return Math.max(FROM, Math.min(TO - 1, LocalTime.now().getHour())); }

  @GetMapping Map<String, Object> run() {
    int now = liveHour(), elapsed = now - FROM + 1;
    LocalDate today = LocalDate.now(); int dow = today.getDayOfWeek().getValue();
    List<Room> rs = rooms.findAll(); List<Reading> all = readings.findByDate(today);
    List<Booking> bs = bookings.findAll().stream().filter(b -> b.day == 0 || b.day == dow).toList();
    List<Map<String, Object>> rows = new ArrayList<>(), recs = new ArrayList<>();
    double waste = 0, utilSum = 0;
    for (Room r : rs) {
      List<Reading> rr = all.stream().filter(x -> x.roomId.equals(r.id)).toList();
      List<Reading> past = rr.stream().filter(x -> x.hour <= now).toList();
      List<Booking> rb = bs.stream().filter(x -> x.roomId.equals(r.id)).toList();
      long used = past.stream().filter(x -> x.headcount > 0).count();
      double kwh = past.stream().filter(x -> x.headcount == 0 && (x.equipmentOn || (assumeOn && rb.stream().anyMatch(bk -> bk.startHour <= x.hour && x.hour < bk.endHour)))).mapToDouble(x -> (x.watts != null ? x.watts : r.watts) / 1000.0).sum();
      int peak = past.stream().mapToInt(x -> x.headcount).max().orElse(0);
      Reading cur = rr.stream().filter(x -> x.hour == now).findFirst().orElse(null);
      boolean booked = rb.stream().anyMatch(b -> b.startHour <= now && now < b.endHour);
      int hc = cur == null ? 0 : cur.headcount;
      String status = r.maintenance ? "Maintenance" : hc > 0 ? (hc < 0.3 * r.capacity ? "Underutilized" : "Occupied") : booked ? "Scheduled" : "Empty";
      double util = 100.0 * used / elapsed; utilSum += util; waste += kwh;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", r.id); row.put("name", r.name); row.put("type", r.type); row.put("building", r.building); row.put("floor", r.floor);
      row.put("x", r.x); row.put("y", r.y); row.put("capacity", r.capacity); row.put("status", status);
      row.put("utilization", Math.round(util)); row.put("unusedHours", elapsed - used); row.put("wasteKwh", Math.round(kwh * 10) / 10.0);
      rows.add(row);
      if (kwh > 0) recs.add(rec("EQUIPMENT_LEFT_ON", r.name, "Equipment ran while the room was empty. Add an auto switch-off.", kwh));
      for (Booking b : rb) {
        if (b.endHour > now) continue;   // class has not finished yet
        List<Reading> during = past.stream().filter(x -> x.hour >= b.startHour && x.hour < b.endHour).toList();
        if (!during.isEmpty() && during.stream().allMatch(x -> x.headcount == 0))
          recs.add(rec("GHOST_BOOKING", r.name, "Booked " + b.startHour + ":00-" + b.endHour + ":00" + (b.course != null && !b.course.isBlank() ? " (" + b.course + ")" : "") + " but nobody came. Release the slot.", (b.endHour - b.startHour) * r.watts / 1000.0));
      }
      if (used > 0 && peak < 0.3 * r.capacity) recs.add(rec("RIGHTSIZE_ROOM", r.name, "Peak attendance was " + peak + " of " + r.capacity + " seats. Move to a smaller room.", 0));
    }
    // consolidation: first-fit-decreasing into the smallest rooms, find the hour that frees the most
    int bestH = -1, bestFree = 0; double bestKwh = 0;
    List<Room> bins = rs.stream().filter(r -> !r.maintenance).sorted(Comparator.comparingInt(r -> r.capacity)).toList();
    for (int h = FROM; h <= now; h++) {
      final int hh = h;
      List<Reading> live = all.stream().filter(x -> x.hour == hh && x.headcount > 0).sorted((a, b) -> b.headcount - a.headcount).toList();
      Map<Long, Integer> left = new HashMap<>(); bins.forEach(r -> left.put(r.id, r.capacity)); Set<Long> usedBins = new HashSet<>();
      for (Reading s : live) for (Room b : bins) if (left.get(b.id) >= s.headcount) { left.merge(b.id, -s.headcount, Integer::sum); usedBins.add(b.id); break; }
      Set<Long> before = live.stream().map(x -> x.roomId).collect(Collectors.toSet());
      int free = before.size() - usedBins.size();
      double kwh = rs.stream().filter(r -> before.contains(r.id) && !usedBins.contains(r.id)).mapToDouble(r -> r.watts / 1000.0).sum();
      if (free > bestFree) { bestFree = free; bestH = h; bestKwh = kwh; }
    }
    if (bestFree > 0) recs.add(rec("CONSOLIDATE_ROOMS", "All rooms", "At " + bestH + ":00 the sessions fit in " + bestFree + " fewer room(s). Pack them into the smallest rooms that fit.", bestKwh));
    double saving = recs.stream().mapToDouble(x -> (double) x.get("kwh")).sum();
    recs.sort((a, b) -> Double.compare((double) b.get("kwh"), (double) a.get("kwh")));
    return Map.of("rooms", rows, "recommendations", recs, "totals", Map.of("wasteKwh", Math.round(waste * 10) / 10.0,
      "savingsKwh", Math.round(saving * 10) / 10.0, "avgUtilization", rs.isEmpty() ? 0 : Math.round(utilSum / rs.size())));
  }
  Map<String, Object> rec(String type, String room, String msg, double kwh) { return Map.of("type", type, "room", room, "message", msg, "kwh", Math.round(kwh * 10) / 10.0); }
}
