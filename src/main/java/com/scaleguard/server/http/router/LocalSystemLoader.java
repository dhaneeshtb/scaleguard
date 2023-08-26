package com.scaleguard.server.http.router;

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class LocalSystemLoader implements SystemLoader {

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
  public List<TargetSystem> loadTargets() {
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
        return ss;
      }).collect(Collectors.toList());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
}
