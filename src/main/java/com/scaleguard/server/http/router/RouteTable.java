package com.scaleguard.server.http.router;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class RouteTable {

  // Read on every request by the I/O threads, written by the admin API and cluster reloads: copy-on-write.
  private volatile List<SourceSystem> sourceSystsems = new CopyOnWriteArrayList<>();
  private final Map<String,TargetSystem> targetSystemMap = new ConcurrentHashMap<>();
  private final List<HostGroup> hostGroups = new CopyOnWriteArrayList<>();
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

  /**
   * Re-reads routes, targets and backends from the database after another cluster node changed them.
   * Backends that still exist keep their identity — and with it their health state, passive-health ejections and
   * open-connection counts; only their configuration is refreshed. Requests in flight finish on the old objects.
   */
  public synchronized void reload(){
    List<HostGroup> freshHosts = loader.loadHostGroups();
    List<SourceSystem> freshSources = loader.loadSources();
    if (freshHosts == null || freshSources == null) {
      throw new IllegalStateException("could not read routes from the database; keeping the current configuration");
    }
    Map<String,HostGroup> current = new HashMap<>();
    hostGroups.forEach(h -> { if (h.getId() != null) current.put(h.getId(), h); });
    List<HostGroup> merged = new ArrayList<>();
    for (HostGroup f : freshHosts) {
      if (f.getId() == null) f.setId(UUID.randomUUID().toString());
      HostGroup cur = current.get(f.getId());
      if (cur != null) {
        copyConfig(f, cur);
        merged.add(cur);
      } else {
        merged.add(f);
      }
    }
    List<TargetSystem> freshTargets = loader.loadTargets(merged);
    if (freshTargets == null) {
      throw new IllegalStateException("could not read targets from the database; keeping the current configuration");
    }
    Set<HostGroup> keep = Collections.newSetFromMap(new IdentityHashMap<>());
    keep.addAll(merged);
    hostGroups.removeIf(h -> !keep.contains(h));
    for (HostGroup h : merged) {
      if (hostGroups.stream().noneMatch(x -> x == h)) hostGroups.add(h);
    }
    Map<String,TargetSystem> tm = new HashMap<>();
    freshTargets.forEach(t -> tm.put(t.getGroupId(), t));
    targetSystemMap.putAll(tm);
    targetSystemMap.keySet().retainAll(tm.keySet());
    freshSources.forEach(src -> src.setTargetSystem(tm.get(src.getTarget())));
    sourceSystsems = new CopyOnWriteArrayList<>(freshSources);
  }

  /** Copies persisted settings from a freshly loaded backend onto the live one, leaving runtime state alone. */
  private static void copyConfig(HostGroup from, HostGroup to){
    for (java.lang.reflect.Field f : HostGroup.class.getDeclaredFields()) {
      int mod = f.getModifiers();
      if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isFinal(mod)
              || java.lang.reflect.Modifier.isTransient(mod) || f.getName().equals("isReachable")) continue;
      f.setAccessible(true);
      try {
        f.set(to, f.get(from));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
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
    if(ss!=null && ss.getTarget()!=null && !ss.getTarget().isEmpty()){
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

  /** Routes listening on this host name and port (any path). Used to choose protocols before a request is read. */
  public List<SourceSystem> routesFor(String host, int port){
    if (host == null) return List.of();
    String p = String.valueOf(port);
    List<SourceSystem> out = new ArrayList<>();
    for (SourceSystem s : sourceSystsems) {
      if (s.getHost() == null) continue;
      String h = s.getHost();
      String sp = s.getPort();
      int colon = h.indexOf(':');
      if (colon > 0) { sp = h.substring(colon + 1); h = h.substring(0, colon); }
      if (h.equalsIgnoreCase(host) && p.equals(sp)) out.add(s);
    }
    return out;
  }

  /** HTTP/2 may be negotiated for this host name on this port: true unless every route for it turned HTTP/2 off. */
  public boolean http2Allowed(String host, int port){
    List<SourceSystem> rs = routesFor(host, port);
    return rs.isEmpty() || rs.stream().anyMatch(SourceSystem::http2Enabled);
  }

  /** HTTP/3 may be used for this host name on this port: true unless every route for it turned HTTP/3 off. */
  public boolean http3Allowed(String host, int port){
    List<SourceSystem> rs = routesFor(host, port);
    return rs.isEmpty() || rs.stream().anyMatch(SourceSystem::http3Enabled);
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
