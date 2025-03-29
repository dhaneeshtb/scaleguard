package com.scaleguard.server.http.async;

import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import java.util.concurrent.SubmissionPublisher;

public class RequestFlowDriver {

    private RequestFlowDriver(){}

    static SubmissionPublisher<ProxyRequest> publisher = new SubmissionPublisher<>();

    static {
        ProxySubscriber<ProxyRequest> subscriber = new ProxySubscriber<>();
        publisher.subscribe(subscriber);

    }
    public static ProxyResponse publish(ProxyRequest pr){
        publisher.submit(pr);
        ProxyResponse prs = new ProxyResponse();
        prs.setId(pr.getId());
        prs.setStatus("pending");
        return prs;
    }

}
