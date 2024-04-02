package com.scaleguard.server.registration;

import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.HostGroupsDB;
import com.scaleguard.server.db.TargetSystemDB;
import com.scaleguard.server.http.router.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DynamicRegistrar {

    static DBSystemLoader dbl = new DBSystemLoader();

    private DynamicRegistrar() {

    }


    public static boolean unRegister(final String targetGroupId, final HostGroup hostGroup) throws Exception {
        List<HostGroup> hgs = dbl.loadHostGroups().stream().filter(h -> h.getGroupId().equalsIgnoreCase(targetGroupId)).collect(Collectors.toList());
        final HostGroup existing;
        if (!hgs.isEmpty()) {
            existing = hgs.stream().filter(hg -> hg.getHost().equalsIgnoreCase(hostGroup.getHost()) && hg.getPort().equalsIgnoreCase(hostGroup.getPort())).findFirst().orElse(null);
        }else{
            existing=null;
        }
        if (existing != null) {
            HostGroupsDB.getInstance().delete(existing.getId());
        }
        return existing != null;
    }



    public static boolean register(final String targetGroupId, final HostGroup hostGroup, boolean createTarget) throws Exception {
        List<DBModelSystem> targets = TargetSystemDB.getInstance().readItems("groupId", targetGroupId);
        if (targets.isEmpty()) {
            if (createTarget) {
                createHG(targetGroupId, hostGroup.getScheme());
            } else {
                throw new IllegalStateException("invalid targetGroupId :" + targetGroupId);
            }
        }
        List<HostGroup> hgs = dbl.loadHostGroups().stream().filter(h -> h.getGroupId().equalsIgnoreCase(targetGroupId)).collect(Collectors.toList());
        HostGroup existing = null;
        if (!hgs.isEmpty()) {
            existing = hgs.stream().filter(hg -> hg.getHost().equalsIgnoreCase(hostGroup.getHost()) && hg.getPort().equalsIgnoreCase(hostGroup.getPort())).findFirst().orElse(null);
        }
        if (existing != null) {
            edit(existing, hostGroup);
        } else {
            hostGroup.setGroupId(targetGroupId);
            HostGroupsDB.getInstance().create(ConfigManager.toDBModel(hostGroup));
        }
        return true;
    }

    private static void edit(HostGroup existing, HostGroup hostGroup) throws Exception {
        if (hostGroup.getLoadFactor() > 0)
            existing.setLoadFactor(hostGroup.getLoadFactor());

        if (hostGroup.getHealth() != null)
            existing.setHealth(hostGroup.getHealth());

        if (hostGroup.getPriority() > 0)
            existing.setPriority(hostGroup.getPriority());

        if (hostGroup.getScheme() != null)
            existing.setScheme(hostGroup.getScheme());

        HostGroupsDB.getInstance().edit(ConfigManager.toDBModel(existing));
    }
    private static void createHG(String targetGroupId, String scheme) throws Exception {
        TargetSystem ts = new TargetSystem();
        ts.setId(UUID.randomUUID().toString());
        ts.setName(targetGroupId);
        ts.setGroupId(targetGroupId);
        ts.setScheme(scheme);
        ts.setBasePath("/");
        ts.setName(targetGroupId);
        TargetSystemDB.getInstance().save(ConfigManager.toDBModel(ts));
    }
}
