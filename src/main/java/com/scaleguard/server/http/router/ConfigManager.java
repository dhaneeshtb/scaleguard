package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.certificates.CertificatesRoute;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.HostGroupsDB;
import com.scaleguard.server.db.SourceSystemDB;
import com.scaleguard.server.db.TargetSystemDB;
import com.scaleguard.server.system.SystemManager;

import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.stream.Collectors;

public class ConfigManager {
    public static ObjectMapper mapper = new ObjectMapper();

    static SubmissionPublisher<SourceSystem> publisher= new SubmissionPublisher<>();

    public static Flow.Publisher<SourceSystem> getPublisher() {
        return publisher;
    }


    public static JsonNode update(String type, String method, String[] params, String json) throws Exception {
        JsonNode out = null;
        switch (type) {
            case "sourcesystems":
                if ("delete".equalsIgnoreCase(method)) {
                    SourceSystemDB.getInstance().delete(params[params.length - 1]);
                    RouteTable.getInstance().deleteSource(params[params.length - 1]);
                } else {
                    SourceSystem ss = mapper.readValue(json, SourceSystem.class);
                    update(ss);
                    out = mapper.valueToTree(ss);
                }
                break;
            case "targetsystems":
                if ("delete".equalsIgnoreCase(method)) {
                    TargetSystemDB.getInstance().delete(params[params.length - 1]);
                    RouteTable.getInstance().deleteTarget(params[params.length - 1]);

                } else{
                    TargetSystem ts = mapper.readValue(json, TargetSystem.class);
                    update(ts);
                    out = mapper.valueToTree(ts);
                }
                break;

            case "hostgroups":
                if ("delete".equalsIgnoreCase(method)) {
                    HostGroupsDB.getInstance().delete(params[params.length - 1]);
                    RouteTable.getInstance().deleteHost(params[params.length - 1]);

                } else {
                    HostGroup hg = LocalSystemLoader.mapper.readValue(json, HostGroup.class);
                    update(hg);
                    out = LocalSystemLoader.mapper.valueToTree(hg);
                }
                break;
            default:
                break;


        }
        return out;
    }

    public static void update(HostGroup s) {
        final HostGroup fHg;

        if(s.getId()==null){
            s.setId(UUID.randomUUID().toString());
            RouteTable.getInstance().getHostGroups().add(s);
            fHg=s;
            try {
                HostGroupsDB.getInstance().create(toDBModel(s));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }else{
            HostGroup hostGroup = RouteTable.getInstance().getHostGroups().stream().filter(hg -> hg.getId().equalsIgnoreCase(s.getId())).findFirst().get();
            copy(s,hostGroup);
            fHg=hostGroup;
            try {
                HostGroupsDB.getInstance().edit(toDBModel(s));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
        RouteTable.getInstance().getTargetSystems().values().forEach(ss -> {
            if (ss.getHostGroupId() != null && ss.getHostGroupId().equalsIgnoreCase(fHg.getGroupId())) {
                ss.getHostGroups().add(fHg);
            }
        });
        RouteTable.getInstance().remapSourceSystems();


    }

    public static void update(TargetSystem ss) {
        final TargetSystem fTs;

        if(ss.getId()==null){
            ss.setId(UUID.randomUUID().toString());
            RouteTable.getInstance().getTargetSystems().put(ss.getGroupId(), ss);
            fTs=ss;
            try {
                TargetSystemDB.getInstance().create(toDBModel(fTs));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }else{
            TargetSystem hostGroup = RouteTable.getInstance().getTargetSystems().values().stream().filter(hg -> hg.getId().equalsIgnoreCase(ss.getId())).findFirst().orElse(null);
            copy(ss,hostGroup);
            fTs=hostGroup;
            try {
                TargetSystemDB.getInstance().edit(toDBModel(ss));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (ss.getHostGroupId() != null) {
            ss.setHostGroups(RouteTable.getInstance().getHostGroups().stream().filter(s -> s.getGroupId().equals(fTs.getHostGroupId())).collect(Collectors.toList()));
        }
        RouteTable.getInstance().remapSourceSystems();
    }

    public static void update(SourceSystem ss) {
        if(ss.getId()==null){
            ss.setId(UUID.randomUUID().toString());
            RouteTable.getInstance().getSourceSystsems().add(ss);

            if(ss.isAutoProcure() && ss.getCertificateId()!=null && !ss.getCertificateId().isEmpty()) {
                try {
                    boolean isMapped =  SystemManager.isSystemMapped(ss.getHost());
                    if(isMapped) {
                        String certificateId = UUID.randomUUID().toString();
                        CertificatesRoute.getCm().orderCertificate(List.of(ss.getHost()), certificateId);
                        ss.setCertificateId(certificateId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            try {
                SourceSystemDB.getInstance().create(toDBModel(ss));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }else{
            SourceSystem hostGroup = RouteTable.getInstance().getSourceSystsems().stream().filter(hg -> hg.getId().equalsIgnoreCase(ss.getId())).findFirst().get();
            copy(ss,hostGroup);
            try {
                SourceSystemDB.getInstance().edit(toDBModel(ss));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
        publisher.submit(ss);
        RouteTable.getInstance().remapSourceSystems();
    }

    private static DBModelSystem toDBModel(SourceSystem d){
        DBModelSystem dm = new DBModelSystem();
        dm.setId(d.getId()!=null?d.getId():UUID.randomUUID().toString());
        dm.setName(d.getGroupId());
        dm.setMts(System.currentTimeMillis());
        dm.setUts(System.currentTimeMillis());
        dm.setPayload(mapper.valueToTree(d).toString());
        return dm;
    }

    private static DBModelSystem toDBModel(TargetSystem d){
        DBModelSystem dm = new DBModelSystem();
        dm.setId(d.getId()!=null?d.getId():UUID.randomUUID().toString());
        dm.setName(d.getGroupId());
        dm.setMts(System.currentTimeMillis());
        dm.setUts(System.currentTimeMillis());
        dm.setPayload(mapper.valueToTree(d).toString());
        return dm;
    }
    private static DBModelSystem toDBModel(HostGroup d){
        DBModelSystem dm = new DBModelSystem();
        dm.setId(d.getId()!=null?d.getId():UUID.randomUUID().toString());
        dm.setName(d.getGroupId());
        dm.setMts(System.currentTimeMillis());
        dm.setUts(System.currentTimeMillis());
        dm.setPayload(mapper.valueToTree(d).toString());
        return dm;
    }

    private static void copy(Object source,Object target){
        Field[] fields = source.getClass().getDeclaredFields();
        for(Field f:fields){
            f.setAccessible(true);
            try {
                Object o=f.get(source);
                if(o!=null)
                    f.set(target,f.get(source));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

    }
}
