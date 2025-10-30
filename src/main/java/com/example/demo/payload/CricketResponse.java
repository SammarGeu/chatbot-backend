package com.example.demo.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CricketResponse {
    @JsonProperty("content")
    private String content;

    public CricketResponse() {}

    public String getContent() {
        return content;
    }
    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "CricketResponse{content='" + content + "'}";
    }
}
