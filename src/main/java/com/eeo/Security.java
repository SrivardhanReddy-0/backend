package com.eeo;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class Security {}

@Component class Jwt {
  SecretKey key;
  Jwt(@Value("${app.jwt-secret}") String s) { key = Keys.hmacShaKeyFor(s.getBytes()); }
  String make(AppUser u) {
    return Jwts.builder().subject(u.username).claim("role", u.role).issuedAt(new Date())
      .expiration(new Date(System.currentTimeMillis() + 8 * 3600_000L)).signWith(key).compact();
  }
  Claims read(String t) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(t).getPayload(); }
}

@Component class JwtFilter extends OncePerRequestFilter {
  Jwt jwt; JwtFilter(Jwt j) { jwt = j; }
  protected boolean shouldNotFilterAsyncDispatch() { return false; }
  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse s, FilterChain c) throws IOException, jakarta.servlet.ServletException {
    String h = q.getHeader("Authorization");
    String t = h != null && h.startsWith("Bearer ") ? h.substring(7) : q.getRequestURI().endsWith("/live/stream") ? q.getParameter("token") : null;
    if (t != null) {
      try {
        Claims cl = jwt.read(t);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
          cl.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + cl.get("role")))));
      } catch (JwtException ignored) {}
    }
    c.doFilter(q, s);
  }
}

@Configuration class SecurityConfig {
  @Bean SecurityFilterChain chain(HttpSecurity http, JwtFilter f) throws Exception {
    http.cors(org.springframework.security.config.Customizer.withDefaults()).csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/**").permitAll()
        .requestMatchers(HttpMethod.POST, "/api/live/readings", "/api/wifi/report").permitAll()
        .requestMatchers(HttpMethod.POST, "/api/live/simulator").hasRole("ADMIN")
        .requestMatchers(HttpMethod.POST, "/api/rooms/**").hasAnyRole("ADMIN", "MANAGER")
        .requestMatchers(HttpMethod.PUT, "/api/rooms/**", "/api/plans/**").hasAnyRole("ADMIN", "MANAGER")
        .requestMatchers(HttpMethod.DELETE, "/api/plans/**").hasAnyRole("ADMIN", "MANAGER")
        .requestMatchers(HttpMethod.POST, "/api/timetable/**", "/api/structure/**", "/api/plans/**").hasAnyRole("ADMIN", "MANAGER")
        .requestMatchers(HttpMethod.POST, "/api/wifi/test").hasRole("ADMIN")
        .requestMatchers("/api/chat/settings", "/api/chat/test").hasRole("ADMIN")
        .requestMatchers(HttpMethod.DELETE, "/api/rooms/**").hasRole("ADMIN")
        .anyRequest().authenticated())
      .addFilterBefore(f, UsernamePasswordAuthenticationFilter.class)
      .exceptionHandling(e -> e.authenticationEntryPoint((q, s, x) -> s.sendError(401)));
    return http.build();
  }
  @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
  /** Lets the separately hosted frontend call this API. Set app.cors-origins (comma separated) to your frontend address. */
  @Bean org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins:http://localhost:5173}") String origins) {
    var c = new org.springframework.web.cors.CorsConfiguration(); c.setAllowedOrigins(List.of(origins.split(","))); c.setAllowedMethods(List.of("*")); c.setAllowedHeaders(List.of("*"));
    var s = new org.springframework.web.cors.UrlBasedCorsConfigurationSource(); s.registerCorsConfiguration("/api/**", c); return s;
  }
}

@RestController @RequestMapping("/api/auth") class AuthController {
  UserRepo users; PasswordEncoder enc; Jwt jwt;
  AuthController(UserRepo u, PasswordEncoder e, Jwt j) { users = u; enc = e; jwt = j; }
  @PostMapping("/login") ResponseEntity<?> login(@RequestBody Map<String, String> b) {
    var u = users.findByUsername(b.getOrDefault("username", ""));
    if (u.isEmpty() || !enc.matches(b.getOrDefault("password", ""), u.get().password)) return ResponseEntity.status(401).body(Map.of("error", "Wrong username or password"));
    return ResponseEntity.ok(Map.of("token", jwt.make(u.get()), "username", u.get().username, "role", u.get().role));
  }
  @PostMapping("/register") ResponseEntity<?> register(@RequestBody Map<String, String> b) {
    String n = b.getOrDefault("username", "").trim(), p = b.getOrDefault("password", "");
    if (n.length() < 3 || p.length() < 6) return ResponseEntity.badRequest().body(Map.of("error", "Username needs 3+ characters and password 6+"));
    if (users.findByUsername(n).isPresent()) return ResponseEntity.status(409).body(Map.of("error", "Username already taken"));
    AppUser u = new AppUser(); u.username = n; u.password = enc.encode(p); u.role = "VIEWER"; users.save(u);
    return ResponseEntity.ok(Map.of("token", jwt.make(u), "username", n, "role", "VIEWER"));
  }
}
