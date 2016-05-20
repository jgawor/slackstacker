package uk.co.azquelt.slackstacker;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class Config {
	
	public List<String> tags;

    public JsonNode filter;

	@JsonProperty("slack-webhook-url")
	public String slackWebhookUrl;
	
	@JsonProperty("state-file")
	public String stateFile;

}
