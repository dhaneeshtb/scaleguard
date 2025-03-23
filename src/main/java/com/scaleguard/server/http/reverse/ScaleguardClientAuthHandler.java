package com.scaleguard.server.http.reverse;

import com.scaleguard.server.application.ClientInfo;
import com.scaleguard.server.http.router.RouteTarget;

public class ScaleguardClientAuthHandler {
    public static boolean checkClientAuth(RouteTarget rt){
        String secappId = rt.getSourceSystem().getSecappid();
        if(secappId!=null && !secappId.isEmpty()){
            if(rt.getHeaders()==null){
                return false;
            }
            String clientId=rt.getHeaders().get("client_id");
            String clientSecret=rt.getHeaders().get("client_secret");

            if(clientId==null || clientSecret==null){
                return false;
            }

            ClientInfo.WrappeClientInfoRecord clientInfoRecord = ClientInfo.get().get(clientId);
            if(clientInfoRecord==null){
                return false;
            }else if(clientInfoRecord.getAppid().equalsIgnoreCase(secappId) && clientInfoRecord.getClientid().equalsIgnoreCase(clientId) && clientInfoRecord.getClientsecret().equalsIgnoreCase(clientSecret)){
                return true;
            }else{
                return false;
            }
        }
        return true;
    }
}
