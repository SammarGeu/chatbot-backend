
package com.example.demo.controller;

import com.example.demo.payload.CricketResponse;
import com.example.demo.service.ChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);


    @Autowired
    private ChatService chatService;

    @GetMapping()
    public String generateResponse(@RequestParam(value = "inputText") String inputText) {

        long start = System.currentTimeMillis();
        String s = chatService.generateResponse(inputText);
        long elapsed = System.currentTimeMillis() - start;
        log.info("generateResponse elapsed={}ms inputLen={}", elapsed, inputText.length());
        return s;
    }

    // streaming endpoint: SSE
//
//    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<String> generateResponseStream(@RequestParam(value = "inputText") String inputText) {
//        return chatService.generateResponseStream(inputText);
//    }


    // imports needed at top of file:
// import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
// import reactor.core.Disposable;
// import reactor.core.publisher.Flux;
// import java.util.concurrent.atomic.AtomicReference;
// import java.time.Duration;

    @GetMapping("/stream")
    public SseEmitter generateResponseStreamSse(@RequestParam(value = "inputText") String inputText) {
        final SseEmitter emitter = new SseEmitter(0L); // 0L = no timeout

        try {
            Flux<String> payloadFlux = chatService.generateResponseStream(inputText);

            // keepalive so proxies don't drop idle connections
            Flux<String> keepalive = Flux.interval(Duration.ofSeconds(15)).map(i -> "__KEEPALIVE__");

            Flux<String> merged = Flux.merge(payloadFlux, keepalive);

            AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
            Disposable sub = merged.subscribe(
                    chunk -> {
                        try {
                            emitter.send(SseEmitter.event().data(chunk));
                        } catch (IOException ioe) {
                            Disposable d = subscriptionRef.get();
                            if (d != null && !d.isDisposed()) d.dispose();
                            try { emitter.complete(); } catch (Exception ignore) {}
                        }
                    },
                    err -> {
                        log.error("Error while streaming to client", err);
                        try { emitter.completeWithError(err); } catch (Exception ignore) {}
                        Disposable d = subscriptionRef.get();
                        if (d != null && !d.isDisposed()) d.dispose();
                    },
                    () -> {
                        try { emitter.complete(); } catch (Exception ignore) {}
                        Disposable d = subscriptionRef.get();
                        if (d != null && !d.isDisposed()) d.dispose();
                    }
            );

            subscriptionRef.set(sub);

            emitter.onCompletion(() -> {
                Disposable d = subscriptionRef.get();
                if (d != null && !d.isDisposed()) d.dispose();
                log.info("Emitter completed by client");
            });

            emitter.onTimeout(() -> {
                Disposable d = subscriptionRef.get();
                if (d != null && !d.isDisposed()) d.dispose();
                try { emitter.complete(); } catch (Exception ignore) {}
                log.info("Emitter timed out");
            });

        } catch (Exception e) {
            log.error("Failed to create SSE emitter", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }



    @GetMapping("/cricketbot")
    public ResponseEntity<CricketResponse> generateCricketResponse(@RequestParam("inputText") String inputText) throws JsonProcessingException {
        return ResponseEntity.ok(chatService.generateCricketResponse(inputText));
    }

    @GetMapping("/images")
    public ResponseEntity<List<String>> generateImages(@RequestParam(value = "imageDescription") String imageDescription,
                                       @RequestParam(value = "size",required = false,defaultValue = "1024x1024") String size,
                                       @RequestParam(value = "numberOfImages",defaultValue = "2",required = false) int numberOfImages,
                                       @RequestParam(value = "imageStyle" ,required = false) String imageStyle
    ) throws IOException {
        {
            return ResponseEntity.ok(chatService.generateImages(numberOfImages, imageDescription,imageStyle,size));
        }

    }
}

