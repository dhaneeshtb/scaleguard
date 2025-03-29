package com.scaleguard.server.http.async;

import java.util.concurrent.Flow;

class ProxySubscriber<T> implements Flow.Subscriber<T> {
    private Flow.Subscription subscription;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        System.out.println("Subscribed!");
        subscription.request(1); // Request one item at a time
    }

    @Override
    public void onNext(T item) {
        System.out.println("Received: " + item);
        subscription.request(1); // Request the next item
    }

    @Override
    public void onError(Throwable t) {
        System.err.println("Error: " + t.getMessage());
    }

    @Override
    public void onComplete() {
        System.out.println("Completed!");
    }
}
