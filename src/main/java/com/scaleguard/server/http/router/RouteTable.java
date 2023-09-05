package com.scaleguard.server.http.router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RouteTable {

  private List<SourceSystem> sourceSystsems = new ArrayList<>();
  private Map<String,TargetSystem> targetSystemMap = new HashMap<>();
  private List<HostGroup> hostGroups = new ArrayList<>();

  private static RouteTable routeTable;
  public static synchronized RouteTable getInstance(){
    if(routeTable==null){
      routeTable = new RouteTable();
    }
    return routeTable;
  }

  private RouteTable(){
    load();
  }

  private void load(){
    SystemLoader loader =new LocalSystemLoader();
    hostGroups.addAll(loader.loadHostGroups());
    new HostGroupWatcher(hostGroups).start();
    sourceSystsems.addAll(loader.loadSources());
    loader.loadTargets(hostGroups).forEach(s->targetSystemMap.put(s.getGroupId(),s));

  }


  public void add(SourceSystem source){
    if(sourceSystsems.stream().anyMatch(s->isMatched(source,s))){
      throw new RuntimeException("Route already exists");
    }
    sourceSystsems.add(source);
  }

  private boolean isMatchedSpecific(SourceSystem source, SourceSystem s) {
    if(source.getHost().equalsIgnoreCase(s.getHost())){
      if(s.getBasePath().equalsIgnoreCase(source.getBasePath())){
        if(s.getKeyLookupMap()!=null && !s.getKeyLookupMap().isEmpty()){
          return isIntersectMaps(source.getKeyLookupMap(),s.getKeyLookupMap());
        }else return false;
      }else if(source.getBasePath().startsWith(s.getBasePath())){
        if(s.getKeyLookupMap()!=null && !s.getKeyLookupMap().isEmpty()){
          return isIntersectMaps(source.getKeyLookupMap(),s.getKeyLookupMap());
        }else return false;
      }
    }
    return false;
  }

  private boolean isIntersectMaps(Map<String,List<String>> sm,Map<String,List<String>> rm){
    if(rm!=null && sm!=null){
      return rm.entrySet().stream().anyMatch((entry) ->

              entry.getValue().stream().filter(s->sm.containsKey(entry.getKey()) && sm.get(entry.getKey()).contains(s))
                      .distinct().findAny().isPresent()
      );
    }else{
      return false;
    }
  }

  private boolean isMatched(SourceSystem source, SourceSystem s) {
    if(source.getHost().equalsIgnoreCase(s.getHost())){
      if(s.getBasePath().equalsIgnoreCase(source.getBasePath())){
        if(s.getKeyLookupMap()!=null && !s.getKeyLookupMap().isEmpty()){
          return isIntersectMaps(source.getKeyLookupMap(),s.getKeyLookupMap());
        }else return true;
      }else if(source.getBasePath().startsWith(s.getBasePath())){
        if(s.getKeyLookupMap()!=null && !s.getKeyLookupMap().isEmpty()){
            return isIntersectMaps(source.getKeyLookupMap(),s.getKeyLookupMap());
        }else return true;
      }
    }
    return false;
  }

  public SourceSystem find(SourceSystem source){
    return sourceSystsems.stream().filter(s->isMatched(source,s)).findFirst().orElse(null);
  }

  public TargetSystem findTarget(SourceSystem source){
    SourceSystem ss =sourceSystsems.stream().filter(s->isMatchedSpecific(source,s)).findFirst().orElse(sourceSystsems.stream().filter(s->isMatched(source,s)).findFirst().orElse(null)) ;
    if(ss!=null){
      return targetSystemMap.get(ss.getTarget());
    }
    return null;
  }

}
