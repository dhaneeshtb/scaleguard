package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.HostGroupsDB;
import com.scaleguard.server.db.SourceSystemDB;
import com.scaleguard.server.db.TargetSystemDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class DBSystemLoader implements SystemLoader {

  private static final Logger LOG = LoggerFactory.getLogger(DBSystemLoader.class);
  public static ObjectMapper mapper = new ObjectMapper();

  public static void main(String[] args) {
    try {

      LocalSystemLoader ls=new LocalSystemLoader();

      DBSystemLoader dbl = new DBSystemLoader();
      List<DBModelSystem> models = new ArrayList<>();
      ls.loadHostGroups().forEach(d->{
        DBModelSystem dm = new DBModelSystem();
        dm.setId(d.getId()!=null?d.getId():UUID.randomUUID().toString());
        dm.setName(d.getGroupId());
        dm.setMts(System.currentTimeMillis());
        dm.setUts(System.currentTimeMillis());
        dm.setPayload(mapper.valueToTree(d).toString());
        models.add(dm);
      });
      HostGroupsDB.getInstance().create(models);

      List<HostGroup> hgs= dbl.loadHostGroups();

      LOG.info("Loaded host groups: {}", hgs);


    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<SourceSystem> loadSources() {
    try {
     return SourceSystemDB.getInstance().readAll().stream().map(s-> {
       try {
         return mapper.readValue(s.getPayload(),SourceSystem.class);
       } catch (IOException e) {
         throw new RuntimeException(e);
       }
     }).collect(Collectors.toList());
    } catch (Exception e) {
      LOG.error("Failed to load source systems", e);
    }
    return null;
  }


  @Override
  public List<TargetSystem> loadTargets(List<HostGroup> hostGroups) {
    try {
      Map<String, Set<HostGroup>> hglistMap = hostGroups.stream().collect(Collectors.groupingBy(c -> c.getGroupId(), Collectors.toSet()));
      try {
        return TargetSystemDB.getInstance().readAll().stream().map(t -> {
          try {
            TargetSystem ts = mapper.readValue(t.getPayload(), TargetSystem.class);
            if(hglistMap.containsKey(ts.getHostGroupId()))
              ts.setHostGroups(hglistMap.get(ts.getHostGroupId()).stream().collect(Collectors.toList()));
            return ts;
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }).collect(Collectors.toList());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }catch (Exception e){
      return null;
    }
  }

  @Override
  public List<HostGroup> loadHostGroups() {
    try {
      return HostGroupsDB.getInstance().readAll().stream().map(s-> {
        try {
          HostGroup hgd= mapper.readValue(s.getPayload(),HostGroup.class);
          if(s.getGroupId()!=null)
          hgd.setGroupId(s.getGroupId());
          hgd.setId(s.getId());
          return hgd;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }).collect(Collectors.toList());
    } catch (Exception e) {
      LOG.error("Failed to load host groups", e);
    }
    return null;
  }
}
