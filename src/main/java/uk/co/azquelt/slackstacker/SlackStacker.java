package uk.co.azquelt.slackstacker;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status.Family;

import org.apache.cxf.transport.common.gzip.GZIPFeature;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import uk.co.azquelt.slackstacker.slack.SlackMessage;
import uk.co.azquelt.slackstacker.stack.Question;
import uk.co.azquelt.slackstacker.stack.QuestionResponse;

public class SlackStacker {
	
	private static ObjectMapper stateMapper;
	
	private static Client client = ClientBuilder.newBuilder()
			.register(JacksonJsonProvider.class) // Allow us to serialise JSON <-> POJO
			.register(GZIPFeature.class) // Allow us to understand GZIP compressed pages
			.build();
   
	public static void main(String[] args) throws IOException {
		
		try {
			stateMapper = new ObjectMapper();
			
			CommandLine arguments = CommandLine.processArgs(args);
			
			Config config = loadConfig(arguments.getConfigFile());
			
			State oldState = loadState(config.stateFile);
			Calendar now = getCalendar();
			
			// if no tags - assume filter is specified
			if (config.tags == null) {
			    config.tags = QuestionFilter.collectTags(config.filter);
			}
			System.out.println("Quering for tags: " + config.tags);

			if (oldState != null) {
				
				if (oldState.backoffUntil != null && now.before(oldState.backoffUntil)) {
					// We've been asked by the StackExchange API to back off, don't run
				    System.out.println("StackExchange requested to back off");
					return;
				}
				
				Calendar cutOffTime = getCutOffTime(oldState.lastUpdated);
				
				QuestionResponse questions = getQuestions(config.tags, cutOffTime);
                System.out.println(questions.items.size());
                
				List<Question> newQuestions = filterQuestions(questions.items, cutOffTime, oldState.idsSeen, config.filter);
				//postQuestions(newQuestions, config.slackWebhookUrl);
               				
				State newState = createNewState(now, questions.items);
				if (questions.backoff > 0) {
					Calendar backoffUntil = getCalendar();
					backoffUntil.add(Calendar.SECOND, questions.backoff);
					newState.backoffUntil = backoffUntil;
				}
				saveState(newState, config.stateFile);
			} else {
				System.out.println("No pre-existing state, setting up default state file");
				State newState = createDefaultState(now);
				saveState(newState, config.stateFile);
			}
		} catch (InvalidArgumentException e) {
			System.err.println(e.getMessage());
		}
		
	}

	private static void saveState(State newState, String stateFileName) throws JsonGenerationException, JsonMappingException, IOException {
		File stateFile = new File(stateFileName);
		stateMapper.writerWithDefaultPrettyPrinter().forType(State.class).writeValue(stateFile, newState);
	}
	
	private static State createDefaultState(Calendar now) {
		State newState = new State();
		newState.lastUpdated = now;
		newState.idsSeen = Collections.emptyList();
		return newState;
	}

	private static State createNewState(Calendar now, List<Question> questions) {
		State newState = new State();
		newState.lastUpdated = now;
		newState.idsSeen = new ArrayList<>();
		for (Question question : questions) {
			newState.idsSeen.add(question.question_id);
		}
		return newState;
	}

	private static void postQuestions(List<Question> newQuestions, String webhookUrl) throws IOException {
		if (newQuestions.size() == 0) {
			return; //Nothing to post!
		}
		
		SlackMessage message = MessageBuilder.buildMessage(newQuestions);
		
		WebTarget target = client.target(webhookUrl);
		Invocation.Builder builder = target.request();

		Response resp = builder.post(Entity.entity(message, MediaType.APPLICATION_JSON_TYPE));
		if (resp.getStatusInfo().getFamily() != Family.SUCCESSFUL) {
			throw new IOException("Error posting questions to slack: " + resp.getStatusInfo().getReasonPhrase());
		}
	}

	private static List<Question> filterQuestions(List<Question> questions, Calendar lastUpdated, List<String> idsSeen, JsonNode filter) {
		List<Question> newQuestions = new ArrayList<>();
		for (Question question : questions) {
		    if (question.last_activity_date.before(lastUpdated)) {
		        continue;
		    }
		    if (idsSeen.contains(question.question_id)) {
		        continue;
		    }
		    System.out.println(question.title + " " + question.tags);
		    if (filter == null || QuestionFilter.evaluate(question.tags, filter)) {
		        System.out.println("  yes");
				newQuestions.add(question);
			} else {
			    System.out.println("  no");
			}
		}
		return newQuestions;
	}

	private static QuestionResponse getQuestions(List<String> tags, Calendar lastUpdated) throws IOException {
		List<Question> items = new ArrayList<Question>();
		long startDate = lastUpdated.getTimeInMillis() / 1000; // convert to seconds
		int page = 1;
        while (true) {
            WebTarget target = client.target("http://api.stackexchange.com/2.2");
            WebTarget questionTarget = target.path("search")
				.queryParam("order", "desc")
				.queryParam("sort", "creation")
				.queryParam("site", "stackoverflow")
				.queryParam("fromdate", startDate)
				.queryParam("page", page)
				.queryParam("tagged", joinTags(tags));
		
            Invocation.Builder builder = questionTarget.request();
            builder.accept(MediaType.APPLICATION_JSON);
		    builder.acceptEncoding("UTF-8");
		
		    Response response = builder.get();
		
		    if (response.getStatus() == 200) {
		        QuestionResponse questionResponse = response.readEntity(QuestionResponse.class);
		        items.addAll(questionResponse.items);
		        if (questionResponse.has_more && questionResponse.backoff == 0) {
		            page++;
		        } else {
		            QuestionResponse r = new QuestionResponse();
		            r.setItems(items);
		            r.backoff = questionResponse.backoff;
		            return r;
		        }
		    } else {
		        System.out.println("Response: " + response.getStatus());
		        String string = response.readEntity(String.class);
		        throw new IOException("Getting questions failed. RC: " + response.getStatus() + " Response: " + string);
		    }
        }
	}
	
    private static Calendar getCalendar() {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return now;
    }
    
    private static Calendar getCutOffTime(Calendar lastUpdated) {
        // Sometimes questions don't appear in the API immediately
        //  Add an additional 30 minutes of leeway
        Calendar cutoffTime = (Calendar) lastUpdated.clone();
        cutoffTime.add(Calendar.MINUTE, -30);
        return cutoffTime;
    }
    
	/**
	 * Joins a list of tags into a semi-colon separated string
	 */
	private static String joinTags(List<String> tags) {
		StringBuilder sb = new StringBuilder();
		
		boolean first = true;
		for (String tag : tags) {
			if (!first) {
				sb.append(";");
			}
			first = false;
			sb.append(tag);
		}
		
		return sb.toString();
	}

	private static State loadState(String stateFileName) throws JsonProcessingException, IOException, InvalidArgumentException {
		if (stateFileName == null) {
			throw new InvalidArgumentException("State file location is not set in config file");
		}
		
		File stateFile = new File(stateFileName);
		
		State state = null;
		
		if (stateFile.exists()) {
			state = stateMapper.readerFor(State.class).readValue(stateFile);
		}
		
		return state;
	}
	
	private static Config loadConfig(File configFile) throws JsonProcessingException, IOException, InvalidArgumentException {
		if (configFile == null) {
			throw new InvalidArgumentException("Config file is not set");
		}
		
		if (!configFile.exists()) {
			throw new InvalidArgumentException("Config file [" + configFile + "] does not exist");
		}
		
		Config config = stateMapper.readerFor(Config.class).readValue(configFile);
		
		return config;
	}
	
}
