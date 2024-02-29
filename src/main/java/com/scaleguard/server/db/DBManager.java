package com.scaleguard.server.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.licencing.licensing.LicenceManager;

import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public abstract class DBManager<T extends DBObject> {
    private final String tableName;
    private final Class<T> persistentClass;

    public abstract void init() throws Exception;

    protected DBManager(String tableName){
        this.tableName=tableName;
        this.persistentClass = (Class<T>) ((ParameterizedType) getClass()
                .getGenericSuperclass()).getActualTypeArguments()[0];
    }

    public void create(T u) throws Exception {
        create(List.of(u));
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
                        if (o != null) {
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
                try {
                    c.createStatement().executeUpdate(insertString);
                } catch (SQLException e) {
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
                List<String> values = new ArrayList<>();
                Arrays.stream(persistentClass.getDeclaredFields()).forEach(f -> {
                    f.setAccessible(true);
                    try {
                        if (!f.getName().equalsIgnoreCase("id")) {
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
                //String valuesC = String.join(",", values);
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
        try (Connection c = ConnectionUtil.getConnection()) {
            c.createStatement().executeUpdate(insertString);
        }
    }

    public void delete(String id) throws Exception {
        init();
        String insertString = "delete from "+tableName+" where id ='"+id+"'";
        try (Connection c = ConnectionUtil.getConnection()) {
            c.createStatement().executeUpdate(insertString);
        }
    }

    public List<T> readAll() throws Exception {
        return readItems(null,null);
    }


    public synchronized int getNextFolderId() throws Exception {
        init();
        String insertString = "select max(id) as id from "+tableName;
        int maxVal = 0;

        try (Connection c = ConnectionUtil.getConnection()) {
            ResultSet rs = c.createStatement().executeQuery(insertString);
            if(rs.next()) {
                maxVal = rs.getInt("id");
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return maxVal+1;
    }

    public List<T> readItems(String name,String value) throws Exception {
        init();
        List<T> users = new ArrayList<>();
        String insertString = "select * from "+tableName+" " + (name!=null ? " where "+name+"=" + value  : "");
        try (Connection c = ConnectionUtil.getConnection()) {
            ResultSet rs = c.createStatement().executeQuery(insertString);
            int count = rs.getMetaData().getColumnCount();
            String[] colNames = new String[count];
            for (int i = 0; i < count; i++) {
                colNames[i] = rs.getMetaData().getColumnName(i + 1);
            }

            while (rs.next()) {
                T u =  persistentClass.newInstance();
                ObjectNode on = LicenceManager.om.createObjectNode();
                for (int i = 0; i < count; i++) {
                    Object obj = rs.getObject(i + 1);
                    if (obj != null) {
                        if (obj instanceof String) {
                            on.put(colNames[i], String.valueOf(obj));
                        }
                        if (obj instanceof Long) {
                            on.put(colNames[i], Long.valueOf(obj.toString()));
                        }
                        if (obj instanceof Double) {
                            on.put(colNames[i], Double.valueOf(obj.toString()));
                        } else {
                            on.put(colNames[i], String.valueOf(obj));
                        }
                    }
                }
                users.add(LicenceManager.om.treeToValue(on, persistentClass));
            }

        }
        return users;
    }


}
