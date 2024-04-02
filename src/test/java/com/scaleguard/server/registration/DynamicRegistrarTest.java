package com.scaleguard.server.registration;

import com.scaleguard.server.http.router.HostGroup;
import org.junit.Assert;
import org.junit.Test;

public class DynamicRegistrarTest {

    @Test
    public void testRegister(){
        HostGroup hg = new HostGroup();
        hg.setScheme("https");
        hg.setHost("1.2.3.4");
        hg.setPort("10000");

        try {
            DynamicRegistrar.register("testtarget",hg,true);
            Assert.assertTrue(DynamicRegistrar.unRegister("testtarget",hg));
            Assert.assertFalse(DynamicRegistrar.unRegister("testtarget",hg));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
