package com.scaleguard.server.http.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
public class AuthUtils{
  private static String adminUser = System.getProperty("adminUser",System.getenv("adminUser"));
  private static String adminPassword = System.getProperty("adminPassword",System.getenv("adminPassword"));
  private static String issuer = System.getProperty("issuer","scaleguard");
  private static String SECRET_KEY = Base64.getEncoder().encodeToString((adminUser+":"+adminPassword).getBytes());
  public static AuthInfo getAuthInfo(String token) {
    try {
      token = token.replace("Bearer ", "");
      Claims claims = Jwts.parser()
          .setSigningKey(SECRET_KEY)
          .parseClaimsJws(token)
          .getBody();
      return new AuthInfo(claims.getSubject(),  Map.ofEntries(claims.entrySet().stream().filter(s->s.getValue()!=null).collect(Collectors.toUnmodifiableList()).toArray(new Map.Entry[0])));
    } catch (Exception e) {
      return null;
    }
  }
  public static boolean isTokenValid(String token) {
    try {
      AuthInfo auth = getAuthInfo(token);
      if (auth!=null && auth.getUserName().equals(adminUser)) {
        return true;
      } else {
        return false;
      }
    }catch (Exception e){
      return false;
    }
  }
  public static String createJWT(long ttlMillis) {
    if(adminUser==null || adminPassword==null){
      throw new RuntimeException("credentials are not set");
    }
    //The JWT signature algorithm we will be using to sign the token
    SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;
    long nowMillis = System.currentTimeMillis();
    Date now = new Date(nowMillis);
    //We will sign our JWT with our ApiKey secret
    byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(SECRET_KEY);
    Key signingKey = new SecretKeySpec(apiKeySecretBytes, signatureAlgorithm.getJcaName());
    //Let's set the JWT Claims
    JwtBuilder builder = Jwts.builder().setId(adminUser)
            .setIssuedAt(now)
            .setSubject(adminUser)
            .setIssuer(issuer)
            .signWith(signatureAlgorithm, signingKey);
    //if it has been specified, let's add the expiration
    if (ttlMillis > 0) {
      long expMillis = nowMillis + ttlMillis;
      Date exp = new Date(expMillis);
      builder.setExpiration(exp);
    }
    //Builds the JWT and serializes it to a compact, URL-safe string
    return builder.compact();
  }

  public static String login(String userName,String password) {
    if(adminUser.equals(userName) && adminPassword.equals(password)){
      return createJWT(24*60*60*1000*365);
    }else{
      throw new RuntimeException("invalid user name or password");
    }

  }


  public static void main(String[] args) {
    System.out.println(getAuthInfo(createJWT(1000)));
  }
}
