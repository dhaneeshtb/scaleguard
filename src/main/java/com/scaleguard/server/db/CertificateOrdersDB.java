package com.scaleguard.server.db;

import com.scaleguard.exceptions.GenericServerProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class CertificateOrdersDB extends DBManager<DBModelSystem> {
    private static final Logger LOGGER
            = LoggerFactory.getLogger(CertificateOrdersDB.class);


    private boolean isInited = false;

    private CertificateOrdersDB() {
        super("certificate_orders");
        try {
            init();
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    private static  CertificateOrdersDB certificateOrdersDB = null;

    public static synchronized CertificateOrdersDB getInstance() {
        if(certificateOrdersDB==null) {
            certificateOrdersDB= new CertificateOrdersDB();
        }
        return certificateOrdersDB;
    }


    public void init() throws Exception {
        if (isInited) {
            return;
        }

        try (Connection connection = ConnectionUtil.getConnection();Statement statement=connection.createStatement()) {
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate(ddl());
            isInited = true;
        } catch (SQLException e) {
            LOGGER.error("error while creating db {}",e.getMessage());
        }
    }

    @Override
    public boolean isStatusSupported() {
        return true;
    }
}
