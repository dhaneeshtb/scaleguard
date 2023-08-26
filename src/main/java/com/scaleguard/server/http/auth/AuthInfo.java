package com.scaleguard.server.http.auth;

public class AuthInfo {

  public AuthInfo(String userName,String lob){
    this.userName=userName;
    this.lob=lob;
  }

  public String getUserName() {
    return userName;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public String getLob() {
    return lob;
  }

  public void setLob(String lob) {
    this.lob = lob;
  }

  private String userName;

  private String lob;

}
