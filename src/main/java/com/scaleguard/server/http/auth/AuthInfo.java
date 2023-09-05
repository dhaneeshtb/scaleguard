package com.scaleguard.server.http.auth;

import java.util.Map;

public class AuthInfo {

  public AuthInfo(String userName, Map<String,Object> keys){
    this.userName=userName;
    this.keys = keys;
  }

  public String getUserName() {
    return userName;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public Map<String,Object> getKeys() {
    return keys;
  }

  public void setKeys(Map<String,Object> keys) {
    this.keys = keys;
  }

  private String userName;

  private Map<String,Object> keys;

}
