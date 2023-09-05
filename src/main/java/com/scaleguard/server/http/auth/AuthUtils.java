package com.scaleguard.server.http.auth;

import com.scaleguard.server.http.utils.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class AuthUtils {

  public static AuthInfo getAuthInfo(String token) {
    try {
      token = token.replace("Bearer ", "");
      Claims claims = Jwts.parser()
          .setSigningKey(AppProperties.get("JWTSecret"))
          .parseClaimsJws(token)
          .getBody();
      return new AuthInfo(claims.getSubject(),  Map.ofEntries(claims.entrySet().stream().filter(s->s.getValue()!=null).collect(Collectors.toUnmodifiableList()).toArray(new Map.Entry[0])));
    } catch (Exception e) {
      return null;
    }
  }
}
