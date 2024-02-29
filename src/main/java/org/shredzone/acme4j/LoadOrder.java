/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2015 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j;

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.KeyPair;
import java.security.Security;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * A simple client test tool.
 * <p>
 * Pass the names of the domains as parameters.
 */
public class LoadOrder {
    AcmeContext context;

    public LoadOrder(AcmeContext context) {
        this.context = context;
    }



    public JsonNode createOrder(Collection<String> domains, boolean loadIfExists) throws AcmeException, IOException {
        String domainHashKey = AcmeUtils.getDomainHashKey(domains);
        return createOrder(domains, domainHashKey);
    }

    public JsonNode createOrder(Collection<String> domains) throws AcmeException, IOException {
        return createOrder(domains, null);
    }

    public JsonNode createOrder(Collection<String> domains, String domainHashKey) throws AcmeException, IOException {
        Order order = domainHashKey != null ? AcmeUtils.readOrder(context, domainHashKey) : null;
        if (order == null) {
            order = context.getAccount().newOrder().domains(domains).create();
            if (domainHashKey == null) {
                domainHashKey = AcmeUtils.getDomainHashKey(domains);
            }
            return AcmeUtils.saveOrder(order, domainHashKey);
        } else {
            return AcmeUtils.toOrderInfoJson(order, domainHashKey);
        }
    }

    public JsonNode loadOrderStatus(String domainHashKey) throws AcmeException, IOException {
        Order order = loadOrder(domainHashKey);
        if (order != null) {
            return AcmeUtils.toOrderInfoJson(order, domainHashKey);
        } else {
            return null;
        }
    }
    public Order loadOrder(String domainHashKey) throws AcmeException, IOException {
        return AcmeUtils.readOrder(context, domainHashKey);
    }
}