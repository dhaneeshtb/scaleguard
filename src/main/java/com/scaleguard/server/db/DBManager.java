package com.scaleguard.server.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.licencing.licensing.LicenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public abstract class DBManager<T extends DBObject> {
    private static final Logger LOG = LoggerFactory.getLogger(DBManager.class);

    private final String tableName;
    private final Class<T> persistentClass;
    private static Map<String, Map<String,String>> fieldsMap=new ConcurrentHashMap<>();

    public abstract void init() throws Exception;

    protected DBManager(String tableName){
        this.tableName=tableName;
        this.persistentClass = (Class<T>) ((ParameterizedType) getClass()
                .getGenericSuperclass()).getActualTypeArguments()[0];
    }

    protected String ddl(){
        String sql = ConnectionUtil.isPostgres() ? "CREATE TABLE IF NOT EXISTS "+tableName+" (\n"
                + "	id text PRIMARY KEY,\n"
                + "	name text NOT NULL,\n"
                + "	groupId text,\n"
                + "	target text,\n"
                + "	payload text,\n"
                + "	status text,\n"
                + "	uts bigint,\n"
                + "	mts bigint\n"
                + ");" :
                "CREATE TABLE IF NOT EXISTS "+tableName+" (\n"
                        + "	id text PRIMARY KEY,\n"
                        + "	name text NOT NULL,\n"
                        + "	groupId text,\n"
                        + "	target text,\n"
                        + "	payload text,\n"
                        + "	status text,\n"
                        + "	uts integer,\n"
                        + "	mts integer\n"
                        + ");";
        sql+="\nCREATE INDEX IF NOT EXISTS system_group_id_idx ON "+tableName+" (groupId);\n" +
                "CREATE INDEX IF NOT EXISTS system_target_idx ON "+tableName+" (target);\n" +
                "CREATE INDEX IF NOT EXISTS system_status_idx ON "+tableName+" (status);\n" +
                "CREATE INDEX IF NOT EXISTS system_name_idx ON "+tableName+" (name);";
        return sql;
    }

    public void create(T u) throws Exception {
        create(List.of(u));
    }
    public void save(T u) throws Exception {
        save(List.of(u));
    }
    public void save(List<T> ulist) throws Exception {
       List<String> sl=readItems("id", ulist.stream().map(u->u.getId()).collect(Collectors.toList())).stream().map(m->m.getId()).collect(Collectors.toList());
        List<T> insert=new ArrayList<>();
        List<T> update=new ArrayList<>();
        ulist.forEach(u->{
                if(sl.contains(u.getId()))
                    update.add(u);
                else
                    insert.add(u);
        });
        if(!insert.isEmpty()){
            create(insert);
        }
        if(!update.isEmpty()){
            edit(update);
        }
    }


    public void create(List<T> cl) throws Exception {
        init();

        try (Connection c = ConnectionUtil.getConnection()) {
            cl.forEach(u -> {
                List<String> keys = new ArrayList<>();
                List<String> values = new ArrayList<>();
                Arrays.stream(u.getClass().getDeclaredFields()).forEach(f -> {
                    f.setAccessible(true);
                    try {
                        Object o = f.get(u);
                        boolean allowed=!(f.getName().equalsIgnoreCase("status") && !isStatusSupported());
                        if (o != null && allowed) {
                            keys.add(f.getName());
                            values.add(sqlValue(f.get(u)));
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                String fields = String.join(",", keys);
                String valuesC = String.join(",", values);
                String insertString = "insert into " + tableName + "(" + fields + ") values(" + valuesC + ")";

                LOG.debug("insert into {}", tableName);

                try {
                    c.createStatement().executeUpdate(insertString);
                } catch (SQLException e) {
                    LOG.error("Failed to insert record into '{}': {}", tableName, insertString, e);
                    throw new RuntimeException(e);
                }
            });
        }
        published(cl.stream().map(DBObject::getId).collect(Collectors.toList()));
    }
    public void edit(T folder) throws Exception {
        edit(List.of(folder));
    }
    public void edit(List<T> folders) throws Exception {
        init();

        try (Connection c = ConnectionUtil.getConnection()) {
            folders.forEach(folder -> {
                List<String> keys = new ArrayList<>();
                Arrays.stream(persistentClass.getDeclaredFields()).forEach(f -> {
                    f.setAccessible(true);
                    try {
                        boolean allowed=!(f.getName().equalsIgnoreCase("status") && !isStatusSupported());
                        if (!f.getName().equalsIgnoreCase("id") && allowed) {
                            Object o = f.get(folder);
                            if (o != null) {
                                keys.add(f.getName() + " = " + sqlValue(f.get(folder)));
                            }
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                String fields = String.join(",", keys);
                String insertString = "update " + tableName + " set " + fields + " where id=" + sqlValue(folder.getId());

                try {
                    c.createStatement().executeUpdate(insertString);
                } catch (SQLException e) {

                    throw new RuntimeException(e);
                }

            });
        }
        published(folders.stream().map(DBObject::getId).collect(Collectors.toList()));
    }

    public void delete(List<T> users) throws Exception {
        init();
        String cond = users.stream().map(u->sqlValue(u.getId())).collect(Collectors.joining(","));
        String insertString = "delete from "+tableName+" where id in ("+cond+")";
        try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(insertString);
        }
        published(users.stream().map(DBObject::getId).collect(Collectors.toList()));
    }

    public void delete(String id) throws Exception {
        init();
        String insertString = "delete from "+tableName+" where id ="+sqlValue(id);
        try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(insertString);
        }
        published(List.of(id));
    }

    /**
     * Cluster scopes a write to these records touches; other nodes reload the subsystems registered for them.
     * Defaults to the table name. Return an empty list for data that isn't configuration.
     */
    protected Collection<String> clusterScopes(Collection<String> ids) {
        return List.of(tableName);
    }

    private void published(Collection<String> ids) {
        try {
            com.scaleguard.server.cluster.ClusterManager.changed(clusterScopes(ids));
        } catch (Throwable e) {
            LOG.debug("cluster change notification failed for {}: {}", tableName, e.toString());
        }
    }

    public List<T> readAll()  {
        try {
            return readItems(null, (List<String>) null);
        }catch (Exception e){
            LOG.error("Failed to read all records from '{}'", tableName, e);
            throw new GenericServerProcessingException(e);
        }
    }


    public List<T> readItems(String name,String value) throws Exception {
       return readItems(name,List.of(value));
    }

    private  Map<String,String> getMappedColumns(){
       return fieldsMap.computeIfAbsent(persistentClass.getName(),k->{
            Field[] fs = persistentClass.getDeclaredFields();
            Map<String,String> fm = new HashMap<>();
            for(Field f:fs){
                f.setAccessible(true);
                fm.put(f.getName().toLowerCase(Locale.ROOT),f.getName());
            }
            return fm;
        });
    }

    public List<T> readItems(String name,List<String> value) throws Exception {
        init();
        List<T> users = new ArrayList<>();
        String instr=value!=null && !value.isEmpty()?value.stream().map(DBManager::sqlValue).collect(Collectors.joining(",")):null;
        String insertString = "select * from "+tableName+" " + (name!=null ? " where "+name+" in (" + instr +")" : "");

        try (Connection c = ConnectionUtil.getConnection();Statement st=c.createStatement()) {
            ResultSet rs = st.executeQuery(insertString);
            int count = rs.getMetaData().getColumnCount();
            String[] colNames = new String[count];
            for (int i = 0; i < count; i++) {
                colNames[i] = rs.getMetaData().getColumnName(i + 1);
            }
            Map<String,String> colMap = getMappedColumns();
            while (rs.next()) {
                ObjectNode on = LicenceManager.om.createObjectNode();
                for (int i = 0; i < count; i++) {
                    Object obj = rs.getObject(i + 1);
                    String rightFiledName = colMap.getOrDefault(colNames[i],colNames[i]);
                    if (obj != null) {
                        if (obj instanceof String) {
                            on.put(rightFiledName, String.valueOf(obj));
                        }
                        if (obj instanceof Long) {
                            on.put(rightFiledName, Long.valueOf(obj.toString()));
                        }
                        if (obj instanceof Double) {
                            on.put(rightFiledName, Double.valueOf(obj.toString()));
                        } else {
                            on.put(rightFiledName, String.valueOf(obj));
                        }
                    }
                }
                users.add(LicenceManager.om.treeToValue(on, persistentClass));
            }

        }
        return users;
    }

    /** Quotes a value as an SQL string literal, escaping embedded quotes so data can't break out of it. */
    static String sqlValue(Object o) {
        return "'" + String.valueOf(o).replace("'", "''") + "'";
    }

    public boolean isStatusSupported(){
        return false;
    }


}
