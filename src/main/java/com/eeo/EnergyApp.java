package com.eeo;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@SpringBootApplication @EnableScheduling
public class EnergyApp { public static void main(String[] a) { SpringApplication.run(EnergyApp.class, a); } }

@Entity class AppUser { @Id @GeneratedValue public Long id; @Column(unique = true) public String username; @com.fasterxml.jackson.annotation.JsonIgnore public String password; public String role; }
@Entity class Room { @Id @GeneratedValue public Long id; public String name, type, building; @Column(name = "floor_label") public String floor; @Column(name = "ap_name") public String apName; public int capacity, watts; public boolean maintenance; public Double x, y; }
// day: 0 = every day, 1 = Monday ... 7 = Sunday
@Entity class Booking { @Id @GeneratedValue public Long id; public Long roomId; @Column(name = "week_day") public int day; public int startHour, endHour, expected; public String course; }
@Entity class Reading { @Id @GeneratedValue public Long id; public Long roomId; @Column(name = "read_date") public LocalDate date; @Column(name = "hour_of_day") public int hour; public int headcount; public boolean equipmentOn; public Integer watts; }
@Entity class Plan { @Id public String id; @Column(columnDefinition = "text") public String image; @Column(columnDefinition = "text") public String holo; @Column(columnDefinition = "text") public String slab; public Boolean deployed; }

interface UserRepo extends JpaRepository<AppUser, Long> { Optional<AppUser> findByUsername(String u); }
interface RoomRepo extends JpaRepository<Room, Long> {}
interface BookingRepo extends JpaRepository<Booking, Long> {}
interface PlanRepo extends JpaRepository<Plan, String> {}
interface ReadingRepo extends JpaRepository<Reading, Long> {
  Optional<Reading> findByRoomIdAndHourAndDate(Long roomId, int hour, LocalDate date);
  List<Reading> findByDate(LocalDate date);
  boolean existsByDate(LocalDate date);
}

@Component class Seeder implements CommandLineRunner {
  UserRepo users; RoomRepo rooms; BookingRepo bookings; ReadingRepo readings; PasswordEncoder enc; StructureService structure; boolean demo; String adminPw, managerPw, viewerPw;
  Seeder(UserRepo u, RoomRepo r, BookingRepo b, ReadingRepo d, PasswordEncoder e, StructureService s, @Value("${app.simulator:true}") boolean demo, @Value("${app.admin-password:admin123}") String adminPw, @Value("${app.manager-password:manager123}") String managerPw, @Value("${app.viewer-password:viewer123}") String viewerPw) { this.adminPw = adminPw; this.managerPw = managerPw; this.viewerPw = viewerPw; users = u; rooms = r; bookings = b; readings = d; enc = e; structure = s; this.demo = demo; }
  void user(String n, String p, String role) { AppUser u = new AppUser(); u.username = n; u.password = enc.encode(p); u.role = role; users.save(u); }
  public void run(String... args) throws Exception {
    if (users.count() == 0) { user("admin", adminPw, "ADMIN"); user("manager", managerPw, "MANAGER"); user("viewer", viewerPw, "VIEWER"); }
    if (!demo) return;   // sample campus and fake readings only in demo mode (app.simulator: true)
    Random rnd = new Random(42);
    if (rooms.count() == 0) {
      String text = new String(new org.springframework.core.io.ClassPathResource("example-structure.txt").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      structure.importText(text, false);
      for (Room r : rooms.findAll()) {
        int s = 8 + rnd.nextInt(3), e = s + 2 + rnd.nextInt(3), s2 = e + 1, e2 = s2 + 2;
        for (int[] w : new int[][]{{s, e}, {s2, e2}}) { Booking b = new Booking(); b.roomId = r.id; b.day = 0; b.course = "Sample class"; b.startHour = w[0]; b.endHour = w[1]; bookings.save(b); }
      }
    }
    LocalDate today = LocalDate.now();
    if (readings.existsByDate(today)) return;
    int dow = today.getDayOfWeek().getValue(); List<Booking> bs = bookings.findAll();
    for (Room r : rooms.findAll()) {
      boolean[] booked = new boolean[25];
      for (Booking b : bs) if (b.roomId.equals(r.id) && (b.day == 0 || b.day == dow)) for (int h = b.startHour; h < b.endHour && h < 24; h++) booked[h] = true;
      for (int h = 8; h < 17; h++) {
        Reading x = new Reading(); x.roomId = r.id; x.date = today; x.hour = h;
        x.headcount = booked[h] && rnd.nextDouble() > 0.25 ? (int) (r.capacity * (0.15 + rnd.nextDouble() * 0.75)) : 0;
        x.equipmentOn = x.headcount > 0 || rnd.nextDouble() < 0.35; readings.save(x);
      }
    }
  }
}
