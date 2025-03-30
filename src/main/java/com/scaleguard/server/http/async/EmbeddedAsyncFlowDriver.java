package com.scaleguard.server.http.async;

import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.http.cache.ProxyRequest;
import com.scaleguard.server.http.cache.ProxyResponse;
import java.util.concurrent.SubmissionPublisher;

public class EmbeddedAsyncFlowDriver implements AsyncFlowDrivers.AsyncFlowDriver {
    private AsyncEngines.WrappedAsyncEngineRecord engineRecord;
    public EmbeddedAsyncFlowDriver(AsyncEngines.WrappedAsyncEngineRecord engineRecord){
        this.engineRecord=engineRecord;
        ProxySubscriber<ProxyRequest> subscriber = new ProxySubscriber<>();
        publisher.subscribe(subscriber);
    }

    SubmissionPublisher<ProxyRequest> publisher = new SubmissionPublisher<>();


    public ProxyResponse publish(ProxyRequest pr){
        publisher.submit(pr);
        ProxyResponse prs = new ProxyResponse();
        prs.setId(pr.getId());
        prs.setStatus("pending");
        return prs;
    }

}
