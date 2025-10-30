package com.example.demo.service;


import com.example.demo.payload.CricketResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.*;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatService {
      @Autowired
    private ChatModel chatModel;

      private Logger log = org.slf4j.LoggerFactory.getLogger(ChatService.class);

    @Value("classpath:prompts/cricket_bot.txt")
    private Resource cricketPromptFile;
      @Autowired
      private  StreamingChatModel streamingChatModel;
     @Autowired
      private ImageModel imageModel;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Path uploadDirPath = Paths.get("uploads");


    public String generateResponse(String inputText) {
       String response= chatModel.call(inputText);
        return response;
    }


    public Flux<String> generateResponseStream(String inputText) {
        if (inputText == null) inputText = "";
        Prompt p = new Prompt(inputText);
        final String finalInputText = inputText;

        // tuned for low latency
        final int maxBatchItems = 5;       // smaller batch -> emit more often
        final long maxWaitMs = 100;        // short timeout -> flush quickly

        try {
            return Flux.from(streamingChatModel.stream(p))
                    .cast(org.springframework.ai.chat.model.ChatResponse.class)
                    // Map ChatResponse -> raw fragment text (cheap)
                    .map(cr -> {
                        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null)
                            return "";
                        String text = cr.getResult().getOutput().getText();
                        return text == null ? "" : text;
                    })
                    .filter(s -> !s.isEmpty())
                    // buffer small batches or flush quickly
                    .bufferTimeout(maxBatchItems, Duration.ofMillis(maxWaitMs))
                    // join into a small chunk string
                    .map(list -> String.join("", list))
                    // lightweight normalize: replace multiple spaces with single space (cheap)
                    .map(big -> {
                        // do minimal normalisation only; avoid expensive regex where possible
                        String normalized = big.replaceAll("[\\t ]+", " ").replace("\r\n", "\n");
                        return normalized;
                    })
                    // split into fairly small readable pieces by newline or length (avoid big paragraph aggregation)
                    .flatMap(chunk -> {
                        // If chunk is short, return as-is
                        if (chunk.length() <= 200) return Flux.just(chunk.trim());
                        // otherwise split into ~200-char slices on whitespace boundaries
                        List<String> out = new ArrayList<>();
                        int idx = 0;
                        while (idx < chunk.length()) {
                            int end = Math.min(chunk.length(), idx + 200);
                            // try to find a space before end to avoid breaking words
                            if (end < chunk.length()) {
                                int lastSpace = chunk.lastIndexOf(' ', end);
                                if (lastSpace > idx) end = lastSpace;
                            }
                            String part = chunk.substring(idx, end).trim();
                            if (!part.isEmpty()) out.add(part);
                            idx = end + 1;
                        }
                        return Flux.fromIterable(out);
                    })
                    .filter(s -> !s.isBlank())
                    // quick debug log to see when chunks are emitted
                    .doOnNext(s -> log.info("[stream chunk] len={} preview='{}'", s.length(),
                            s.substring(0, Math.min(40, s.length())).replace("\n", " ")))
                    // signal end-of-stream to clients (single sentinel value)
                    .concatWith(Flux.just("__END__"))
                    // if streaming fails, fallback — run blocking fallback on boundedElastic to avoid blocking reactor threads
                    .onErrorResume(err -> {
                        log.error("Streaming error, falling back: {}", err.toString());
                        // perform blocking call on boundedElastic scheduler
                        return Mono.fromCallable(() -> {
                                    try {
                                        return chatModel.call(finalInputText);
                                    } catch (Exception callEx) {
                                        log.error("Fallback call error: {}", callEx.toString());
                                        return "[streaming error] " + (callEx.getMessage() == null ? "unknown" : callEx.getMessage());
                                    }
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(full -> {
                                    if (full == null || full.isEmpty()) return Flux.empty();
                                    // emit fallback result in small pieces so UI still gets progressive bits
                                    List<String> pieces = splitIntoPieces(full, 200);

                                    return Flux.fromIterable(pieces);
                                });
                    });



        } catch (Exception ex) {
            log.error("generateResponseStream threw: {}", ex.toString());
            // fallback run in boundedElastic to avoid blocking reactor
            String finalInputText1 = inputText;
            return Mono.fromCallable(() -> {
                try {
                    return chatModel.call(finalInputText1);
                } catch (Exception callEx) {
                    return "[internal error] " + (callEx.getMessage() == null ? "unknown" : callEx.getMessage());
                }
            }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(full -> {
                if (full == null || full.isEmpty()) return Flux.empty();
                return Flux.fromIterable(splitIntoPieces(full, 200));
            }).concatWith(Flux.just("__END__"));
        }
    }



    // small helper to split into readable pieces
    private static List<String> splitIntoPieces(String text, int approxSize) {
        List<String> out = new ArrayList<>();
        int idx = 0;
        while (idx < text.length()) {
            int end = Math.min(text.length(), idx + approxSize);
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > idx) end = lastSpace;
            }
            String part = text.substring(idx, end).trim();
            if (!part.isEmpty()) out.add(part);
            idx = end + 1;
        }
        return out;
    }






    public CricketResponse generateCricketResponse(String inputText) throws JsonProcessingException {
        // 1️⃣ Read prompt template
        String promptTemplate = "";
        try (InputStream is = cricketPromptFile.getInputStream()) {
            promptTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read cricket_bot.txt prompt file", e);
        }

        // 2️⃣ Replace {inputText} placeholder with actual user input
        String finalPrompt = promptTemplate.replace("{inputText}", inputText == null ? "" : inputText.trim());
        System.out.println("=== FINAL PROMPT SENT TO MODEL ===");
        System.out.println(finalPrompt);
        System.out.println("=== END PROMPT ===");

        // 3️⃣ Call model with the proper prompt
        ChatResponse cricketResponse = chatModel.call(new Prompt(finalPrompt));

        // 4️⃣ Extract raw text
        String raw = cricketResponse.getResult().getOutput().getText();
        System.out.println("=== RAW MODEL OUTPUT ===");
        System.out.println(raw);
        System.out.println("=== END RAW MODEL OUTPUT ===");

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (raw != null) {
            raw = raw.trim();

            // If it's quoted JSON
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = mapper.readValue(raw, String.class);
            }

            // If it's code-fenced markdown
            if (raw.startsWith("```")) {
                int firstLine = raw.indexOf('\n');
                if (firstLine > 0) raw = raw.substring(firstLine + 1).trim();
                if (raw.endsWith("```")) raw = raw.substring(0, raw.length() - 3).trim();
            }

            // Try parse as JSON and pull "content"
            try {
                JsonNode root = mapper.readTree(raw);
                if (root.has("content")) {
                    String contentVal = root.get("content").asText("");
                    CricketResponse result = new CricketResponse();
                    result.setContent(contentVal);
                    return result;
                }
            } catch (Exception ignore) {
                // Not JSON, just return text
            }
        }

        // 5️⃣ Return raw response as fallback
        CricketResponse fallback = new CricketResponse();
        fallback.setContent(raw == null ? "No response from model" : raw);
        return fallback;
    }



    public String loadPromptTemplate(String templateName) throws IOException {

       Path filePath= new ClassPathResource(templateName).getFile().toPath();

       return Files.readString(filePath);
    }


    public String putValuesInPromptTemplate(String template, Map<String, String> values) {
        if (template == null || values == null || values.isEmpty()) return template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().trim();               // trim key
            // match {key} or { key } with optional whitespace
            String placeholderRegex = "\\{\\s*" + Pattern.quote(key) + "\\s*\\}";
            template = template.replaceAll(placeholderRegex, Matcher.quoteReplacement(entry.getValue()));
        }
        return template;
    }


    public List<String> generateImages(int numberOfImages, String imageDescription, String imageStyle, String size) throws IOException {
        // ensure upload dir exists
        try {
            Files.createDirectories(uploadDirPath);
        } catch (IOException e) {
            // if we can't create the directory, fail fast
            throw new IOException("Unable to create uploads directory: " + uploadDirPath.toAbsolutePath(), e);
        }

        // Build the prompt using your existing helpers
        String template = this.loadPromptTemplate("prompts/image_bot.txt");
        String promptString = this.putValuesInPromptTemplate(template, Map.of(
                "imageDescription", imageDescription
        ));

        // call the image model (same as before)
        ImageResponse imageResponse = imageModel.call(new ImagePrompt(promptString,
                OpenAiImageOptions.builder()
                        .model("dall-e-2")    // recommended; change to "dall-e-2" if your wrapper requires it
                        .N(numberOfImages)
                        .width(parseWidth(size))
                        .height(parseHeight(size))
                        .build()
        ));


        // Fetch each signed URL immediately, save locally, return local URLs
        List<String> savedPaths = imageResponse.getResults().stream().map(generation -> {
            try {
                String signedUrl = generation.getOutput().getUrl();
                // get bytes from provider
                byte[] bytes = fetchBytesDirectly(signedUrl);
                if (bytes == null) {
                    return null; // fetch failed for this image
                }

                // determine extension from content-type or bytes
                String contentType = probeContentType(bytes);
                String ext = extensionFromContentType(contentType);

                // generate filename and save
                String filename = "img-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ext;
                Path dest = uploadDirPath.resolve(filename);
                Files.write(dest, bytes, StandardOpenOption.CREATE_NEW);

                // return the public path you will serve from (see WebConfig below)
                return "/uploads/" + filename;
            } catch (Exception e) {
                // log if you have logger (System.out fallback)
                System.out.println("Failed to fetch/save image: " + e.getMessage());
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());

        return savedPaths;
    }

// small helpers used above (add inside same ChatService class)

    private int parseWidth(String size) {
        if (size == null) return 1024;
        try {
            if (size.contains("x")) return Integer.parseInt(size.split("x")[0].trim());
        } catch (Exception ignored) {}
        return 1024;
    }

    private int parseHeight(String size) {
        if (size == null) return 1024;
        try {
            if (size.contains("x")) return Integer.parseInt(size.split("x")[1].trim());
        } catch (Exception ignored) {}
        return 1024;
    }

    private String probeContentType(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            String ct = URLConnection.guessContentTypeFromStream(is);
            return ct == null ? "image/png" : ct;
        } catch (IOException e) {
            return "image/png";
        }
    }

    private String extensionFromContentType(String ct) {
        if (ct == null) return ".png";
        ct = ct.toLowerCase(Locale.ROOT);
        if (ct.contains("jpeg") || ct.contains("jpg")) return ".jpg";
        if (ct.contains("gif")) return ".gif";
        if (ct.contains("webp")) return ".webp";
        if (ct.contains("png")) return ".png";
        return ".png";
    }


    private byte[] fetchBytesDirectly(String signedUrl) {
        try {
            // 1) DEBUG: print the signed url so you can inspect se/ske/sig
            System.out.println("DEBUG: provider signedUrl = " + signedUrl);

            // 2) Attempt to extract expiry param for diagnostics
            try {
                URI u = URI.create(signedUrl);
                String q = u.getQuery();
                if (q != null) {
                    for (String p : q.split("&")) {
                        if (p.startsWith("se=") || p.startsWith("ske=")) {
                            String val = p.substring(p.indexOf('=') + 1);
                            String decoded = URLDecoder.decode(val, StandardCharsets.UTF_8);
                            System.out.println("DEBUG: expiry param (raw) = " + decoded);
                            try {
                                Instant expiry = Instant.parse(decoded);
                                System.out.println("DEBUG: expiry parsed = " + expiry + ", now = " + Instant.now());
                            } catch (Exception ignore) { /* ignore parse errors */ }
                        }
                    }
                }
            } catch (Exception ex) {
                System.out.println("DEBUG: cannot parse URL for expiry: " + ex.getMessage());
            }

            // 3) Use RequestEntity with pre-built URI to avoid RestTemplate re-encoding the query string
            URI uri = URI.create(signedUrl);
            RequestEntity<Void> request = RequestEntity.get(uri)
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .build();

            ResponseEntity<byte[]> response = restTemplate.exchange(request, byte[].class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                System.out.println("DEBUG: provider GET failed status=" + response.getStatusCode());
                return null;
            }
            return response.getBody();
        } catch (HttpClientErrorException he) {
            // Azure returns XML body with error details — print it for debugging
            try {
                System.out.println("DEBUG: HttpClientErrorException: " + he.getStatusCode() + " body: " + he.getResponseBodyAsString());
            } catch (Exception ignore) {}
            return null;
        } catch (Exception ex) {
            System.out.println("DEBUG: fetchBytesDirectly exception: " + ex.getMessage());
            return null;
        }
    }

}
