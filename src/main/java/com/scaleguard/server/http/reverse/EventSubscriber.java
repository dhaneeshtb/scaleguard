package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.SourceSystem;

import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

public class EventSubscriber implements Subscriber<SourceSystem> {
    private final AppServer appServer;
    private Subscription subscription;

    public EventSubscriber(AppServer appServer ){
        this.appServer=appServer;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(SourceSystem item) {
        try {
            appServer.addListener(item);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        subscription.request(1);

    }

    @Override
    public void onError(Throwable throwable) {

    }

    @Override
    public void onComplete() {

    }
}