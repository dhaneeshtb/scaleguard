package com.scaleguard.server.http.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

public class AuthUtils {

  public static AuthInfo getAuthInfo(String token) {
    try {
      token = token.replace("Bearer ", "");

      Claims claims = Jwts.parser()
          .setSigningKey("YXBwbGljYXRl")
          .parseClaimsJws(token)
          .getBody();
      return new AuthInfo(claims.getSubject(), claims.get("lob").toString());
    } catch (Exception e) {
      return null;
    }
  }
}
