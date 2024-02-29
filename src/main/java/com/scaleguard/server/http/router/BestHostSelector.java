package com.scaleguard.server.http.router;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class BestHostSelector {
    static Random rn = new Random();

    public static HostGroup getBestHost(List<HostGroup> hostGroups){
        List<HostGroup> reachableHosts =  hostGroups.stream().filter(s->s.isReachable()).collect(Collectors.toList());
        if(reachableHosts!=null && reachableHosts.size()>0) {
            Collections.sort(reachableHosts, Comparator.comparing(HostGroup::getType));
            return reachableHosts.get(0);
        }else{
            return null;
        }
    }





}
