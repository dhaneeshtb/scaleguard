package com.scaleguard.server.http.reverse;

import com.scaleguard.server.acmechallengeroute.AcmeChannelgeRoute;
import com.scaleguard.server.application.AppRoute;
import com.scaleguard.server.application.ClientRoute;
import com.scaleguard.server.certificates.CertificatesRoute;
import com.scaleguard.server.dns.DNSRoute;
import com.scaleguard.server.login.LoginRoute;
import com.scaleguard.server.registration.RegisterRoute;
import com.scaleguard.server.system.SystemsRoute;

import java.util.HashMap;
import java.util.Map;

public class RequestRoutingContexts {

    private RequestRoutingContexts(){}

    public static final Map<String,RequestRoute> routesMap=new HashMap<>();

    static {
        routesMap.put("certificates",new CertificatesRoute());
        routesMap.put("systems",new SystemsRoute());
        routesMap.put("signin",new LoginRoute());
        routesMap.put("dns",new DNSRoute());
        routesMap.put("register",new RegisterRoute());
        routesMap.put("apps",new AppRoute());
        routesMap.put("clients",new ClientRoute());
        routesMap.put(".well-known",new AcmeChannelgeRoute());



    }

}
