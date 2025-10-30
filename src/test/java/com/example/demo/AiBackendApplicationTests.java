package com.example.demo;

import com.example.demo.payload.CricketResponse;
import com.example.demo.service.ChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

@SpringBootTest
class AiBackendApplicationTests {

	protected Logger logger;
	@Autowired
	private ChatService chatService;

	@Test
	void contextLoads()  throws JsonProcessingException {
		CricketResponse cricketResponse = chatService.generateCricketResponse("Who is Alastair Cook?");
		// print the parsed content directly
		System.out.println("=== PARSED CONTENT ===");
		System.out.println(cricketResponse.getContent());
		System.out.println("=== END PARSED CONTENT ===");
	}

	@Test
	void testTemplate() throws IOException {

	String response=chatService.loadPromptTemplate("prompts/cricket_bot.txt");

	String prompt =chatService.putValuesInPromptTemplate(response, Map.of("inputText ","Who is Alastair Cook?"));
		System.out.println(prompt);
	}

//	void testImageGeneration() throws IOException {
//
//		logger.info("Image prompt being sent: >>>\n{}\n<<<", promptString);
//
//// after call
//		logger.info("ImageResponse raw: {}", imageResponse);
//		imageResponse.getResults().forEach(r -> {
//			logger.info("generation id: {}, created: {}, output url: {}",
//					r.getId(), r.getCreated(), r.getOutput().getUrl());
//		});
//	}
}
