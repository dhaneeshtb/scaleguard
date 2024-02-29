package org.shredzone.acme4j;

import java.security.KeyPair;

public class AcmeContext {


    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    private Account account;
    KeyPair domainKeyPair;

    public KeyPair getDomainKeyPair() {
        return domainKeyPair;
    }

    public void setDomainKeyPair(KeyPair domainKeyPair) {
        this.domainKeyPair = domainKeyPair;
    }
}
