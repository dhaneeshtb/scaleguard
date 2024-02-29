package com.scaleguard.server.http.router;

import java.util.*;

public class RouteTable {

  private List<SourceSystem> sourceSystsems = new ArrayList<>();
  private Map<String,TargetSystem> targetSystemMap = new HashMap<>();
  private List<HostGroup> hostGroups = new ArrayList<>();
  static SystemLoader loader =new DBSystemLoader();

  public static SystemLoader getLoader() {
    return loader;
  }

  public static void setLoader(SystemLoader loader) {
    RouteTable.loader = loader;
  }

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

  public void load(){
    hostGroups.clear();
    sourceSystsems.clear();
    targetSystemMap.clear();
    hostGroups.addAll(loader.loadHostGroups());
    hostGroups.forEach(s->{
      if(s.getId()==null){
        s.setId(UUID.randomUUID().toString());
      }
    });
    new HostGroupWatcher(hostGroups).start();
    sourceSystsems.addAll(loader.loadSources());
    loader.loadTargets(hostGroups).forEach(s->targetSystemMap.put(s.getGroupId(),s));
    remapSourceSystems();
  }

  public void deleteSource(String id){
    sourceSystsems.removeIf(st -> st.getId().equalsIgnoreCase(id));
  }
  public void deleteHost(String id){
    hostGroups.removeIf(st -> st.getId().equalsIgnoreCase(id));
  }

  public void deleteTarget(String id){
    targetSystemMap.entrySet().removeIf(st -> st.getValue().getId().equalsIgnoreCase(id));
  }

  public void remapSourceSystems(){
    Map<String,TargetSystem> targetMap =  getTargetSystems();
    getSourceSystsems().forEach(s-> s.setTargetSystem(targetMap.get(s.getTarget())));

  }


  public void add(SourceSystem source){
    if(sourceSystsems.stream().anyMatch(s->isMatched(source,s))){
      throw new RuntimeException("Route already exists");
    }
    sourceSystsems.add(source);
  }

  private boolean isMatchedSpecific(SourceSystem source, SourceSystem s) {
    String incomingSP = source.getHost();
    if(!incomingSP.contains(":")){
      incomingSP = source.getHost()+ ":"+source.getPort();
    }
    String incomingSPMatch = s.getHost();
    if(!incomingSPMatch.contains(":")){
      incomingSPMatch = s.getHost()+ ":"+s.getPort();
    }

    if(incomingSP.equalsIgnoreCase(incomingSPMatch)){
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

    String incomingSP = source.getHost();
    if(!incomingSP.contains(":")){
      incomingSP = source.getHost()+ ":"+source.getPort();
    }
    String incomingSPMatch = s.getHost();
    if(!incomingSPMatch.contains(":")){
      incomingSPMatch = s.getHost()+ ":"+s.getPort();
    }

    if(incomingSP.equalsIgnoreCase(incomingSPMatch)){
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

  private boolean isMatchingHost(SourceSystem source, String host,int port){
    String incomingSP = source.getHost();
    if(!incomingSP.contains(":")){
      incomingSP = source.getHost()+ ":"+source.getPort();
    }
    return incomingSP.equalsIgnoreCase(host+":"+port);
  }

  public SourceSystem find(SourceSystem source){
    return sourceSystsems.stream().filter(s->isMatched(source,s)).findFirst().orElse(null);
  }

  public SourceSystem findMatchingSourceHost(String host,int port){

    return sourceSystsems.stream().filter(s->isMatchingHost(s,host,port)).findFirst().orElse(null);
  }

  public RouteTarget findTarget(SourceSystem source){
    SourceSystem ss =sourceSystsems.stream().filter(s->isMatchedSpecific(source,s)).findFirst().orElse(sourceSystsems.stream().filter(s->isMatched(source,s)).findFirst().orElse(null)) ;
    if(ss!=null){
      TargetSystem ts =  targetSystemMap.get(ss.getTarget());
      return new RouteTarget(ss,ts);
    }
    return null;
  }

  public RouteTarget findTarget(String id){
    SourceSystem ss =sourceSystsems.stream().filter(s->s.getId().equalsIgnoreCase(id)).findFirst().orElse(null) ;
    if(ss!=null){
      TargetSystem ts =  targetSystemMap.get(ss.getTarget());
      return new RouteTarget(ss,ts);
    }
    return null;
  }

  public List<SourceSystem> getSourceSystsems(){
    return  sourceSystsems;
  }
  public Map<String, TargetSystem> getTargetSystems(){
    return  targetSystemMap;
  }

  public List<HostGroup> getHostGroups(){
    return  hostGroups;
  }

}
