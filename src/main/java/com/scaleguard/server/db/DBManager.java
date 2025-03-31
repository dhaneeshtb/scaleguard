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
                            values.add("'" + f.get(u).toString() + "'");
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                String fields = String.join(",", keys);
                String valuesC = String.join(",", values);
                String insertString = "insert into " + tableName + "(" + fields + ") values(" + valuesC + ")";

                LOG.info("create certificate object {}",insertString);

                try {
                    c.createStatement().executeUpdate(insertString);
                } catch (SQLException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
        }
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
                                keys.add(f.getName() + " = " + "'" + f.get(folder).toString() + "'");
                            }
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
                String fields = String.join(",", keys);
                String insertString = "update " + tableName + " set " + fields + " where id='" + folder.getId()+"'";

                try {
                    c.createStatement().executeUpdate(insertString);
                } catch (SQLException e) {

                    throw new RuntimeException(e);
                }

            });
        }
    }

    public void delete(List<T> users) throws Exception {
        init();
        String cond = users.stream().map(u->"'"+u.getId()+"'").collect(Collectors.joining(","));
        String insertString = "delete from "+tableName+" where id in ("+cond+")";
        try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(insertString);
        }
    }

    public void delete(String id) throws Exception {
        init();
        String insertString = "delete from "+tableName+" where id ='"+id+"'";
        try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(insertString);
        }
    }

    public List<T> readAll()  {
        try {
            return readItems(null, (List<String>) null);
        }catch (Exception e){
            e.printStackTrace();
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
        String instr=value!=null && !value.isEmpty()?value.stream().map(r->"'"+r+"'").collect(Collectors.joining(",")):null;
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

    public boolean isStatusSupported(){
        return false;
    }


}
