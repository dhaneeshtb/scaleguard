package com.scaleguard.server.http.async;

import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;

import java.io.IOException;
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
        if(item instanceof ProxyRequest){
            try {
              ProxyResponse proxyResponse= OutboundDispatchUtil.sendRequest(((ProxyRequest)item));
                System.out.println(proxyResponse.getResponseBody());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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
