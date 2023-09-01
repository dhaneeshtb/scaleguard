package com.scaleguard.server.http.router;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.http.cache.CachedResource;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class LocalSystemLoader implements SystemLoader {

  public static ObjectMapper mapper = new ObjectMapper();

  @Override
  public List<SourceSystem> loadSources() {
    try {
      Properties props = new Properties();
      props.load(new FileInputStream("source.properties"));
      return props.keySet().stream().filter(s->s.toString().endsWith(".host")).map(k->{
        String key = k.toString().split("[.]")[1];
        SourceSystem ss = new SourceSystem();
        ss.setHost(props.getProperty(k.toString()));
        ss.setPort(props.getProperty("source."+key+".port"));
        ss.setScheme(props.getProperty("scheme."+key+".scheme"));
        ss.setId(props.getProperty("source."+key+".id"));
        ss.setName(props.getProperty("source."+key+".name"));
        ss.setGroupId(props.getProperty("source."+key+".groupId"));
        ss.setTarget(props.getProperty("source."+key+".target"));
        ss.setAsync(Boolean.valueOf(props.getProperty("source."+key+".async")));
        ss.setBasePath(props.getProperty("source."+key+".basePath"));
        ss.setCallbackId(props.getProperty("source."+key+".callbackId"));
        ss.setJwtKeylookup(props.getProperty("source."+key+".jwtKeylookup"));
        return ss;
      }).collect(Collectors.toList());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public List<TargetSystem> loadTargets(List<HostGroup> hostGroups) {
    try {
      Properties props = new Properties();
      props.load(new FileInputStream("target.properties"));
      return props.keySet().stream().filter(s->s.toString().endsWith(".host")).map(k->{
        String key = k.toString().split("[.]")[1];
        TargetSystem ss = new TargetSystem();
        ss.setHost(props.getProperty(k.toString()));
        ss.setPort(props.getProperty("target."+key+".port"));
        ss.setScheme(props.getProperty("target."+key+".scheme"));
        ss.setId(props.getProperty("target."+key+".id"));
        ss.setName(props.getProperty("target."+key+".name"));
        ss.setGroupId(props.getProperty("target."+key+".groupId"));
        ss.setBasePath(props.getProperty("target."+key+".basePath"));
        ss.setEnableCache(Boolean.parseBoolean(props.getProperty("target."+key+".enableCache")));
        String hgId=props.getProperty("target."+key+".hostgroup");
        String cachePatterns=props.getProperty("target."+key+".cache.patterns");
        if(cachePatterns!=null && !cachePatterns.isEmpty()) {
          try {
            ss.setCachedResources(mapper.readValue(cachePatterns, new TypeReference<List<CachedResource>>() {}));
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
        if(hgId!=null && hostGroups!=null){
          ss.setHostGroups(hostGroups.stream().filter(s->s.getGroupId().equals(hgId)).collect(Collectors.toList()));
        }
        return ss;
      }).collect(Collectors.toList());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public List<HostGroup> loadHostGroups() {
    try {
      Properties props = new Properties();
      props.load(new FileInputStream("hostgroup.properties"));
      return props.keySet().stream().filter(s->s.toString().endsWith(".host")).map(k->{
        String key = k.toString().split("[.]")[1];
        HostGroup ss = new HostGroup();
        ss.setHost(props.getProperty(k.toString()));
        ss.setPort(props.getProperty("hostgroup."+key+".port"));
        ss.setGroupId(props.getProperty("hostgroup."+key+".groupId"));
        ss.setScheme(props.getProperty("hostgroup."+key+".scheme"));
        ss.setType(props.getProperty("hostgroup."+key+".type"));
        ss.setHealth(props.getProperty("hostgroup."+key+".health"));
        return ss;
      }).collect(Collectors.toList());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
}
