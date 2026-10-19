package com.personaltrader.committee;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels before oversized data reaches the accumulating subscriber. No body logging. */
final class BoundedResponseSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
    private final long limit;
    private long received;
    private boolean stopped;
    private Flow.Subscription subscription;
    BoundedResponseSubscriber(long limit){if(limit<=0)throw new IllegalArgumentException();this.limit=limit;}
    @Override public CompletionStage<byte[]> getBody(){return delegate.getBody();}
    @Override public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
    @Override public void onNext(List<ByteBuffer> items){
        if(stopped)return;
        for(var item:items){received+=item.remaining();if(received>limit){
            stopped=true;subscription.cancel();delegate.onError(new IOException("AI response size limit exceeded"));return;
        }}
        delegate.onNext(items);
    }
    @Override public void onError(Throwable error){if(!stopped){stopped=true;delegate.onError(error);}}
    @Override public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
}
