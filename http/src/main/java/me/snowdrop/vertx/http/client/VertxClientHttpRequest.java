package me.snowdrop.vertx.http.client;

import java.net.URI;
import java.util.Collection;

import io.vertx.core.http.HttpClientRequest;
import me.snowdrop.vertx.http.utils.BufferConverter;
import me.snowdrop.vertx.http.common.PublisherToHttpBodyConnector;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.AbstractClientHttpRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static java.util.function.Function.identity;

public class VertxClientHttpRequest extends AbstractClientHttpRequest {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final HttpClientRequest delegate;

    private final BufferConverter bufferConverter;

    public VertxClientHttpRequest(HttpClientRequest delegate, BufferConverter bufferConverter) {
        this.delegate = delegate;
        this.bufferConverter = bufferConverter;
    }

    @Override
    public HttpMethod getMethod() {
        return HttpMethod.resolve(delegate.method().name());
    }

    @Override
    public URI getURI() {
        return URI.create(delegate.absoluteURI());
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return bufferConverter.getDataBufferFactory();
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> chunks) {
        Mono<Void> writeCompletion = Mono.create(sink -> {
            logger.debug("Subscribing to body publisher");
            chunks.subscribe(new PublisherToHttpBodyConnector(delegate, sink, bufferConverter));
        });

        Mono<Void> endCompletion = Mono.create(sink -> {
            logger.debug("Completing request after writing");
            delegate.end();
            sink.success();
        });

        return doCommit(() -> writeCompletion.then(endCompletion));
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> chunks) {
        return writeWith(Flux.from(chunks).flatMap(identity()));
    }

    @Override
    public Mono<Void> setComplete() {
        return doCommit(() -> Mono.create(sink -> {
            logger.debug("Completing empty request");
            delegate.end();
            sink.success();
        }));
    }

    @Override
    protected void applyHeaders() {
        HttpHeaders headers = getHeaders();
        if (!headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
            logger.debug("Setting chunked request");
            delegate.setChunked(true);
        }
        headers.forEach(delegate::putHeader);
    }

    @Override
    protected void applyCookies() {
        getCookies()
            .values()
            .stream()
            .flatMap(Collection::stream)
            .map(HttpCookie::toString)
            .forEach(cookie -> delegate.putHeader("Cookie", cookie));
    }
}