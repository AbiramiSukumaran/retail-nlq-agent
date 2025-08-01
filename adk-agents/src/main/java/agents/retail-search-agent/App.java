package agents;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import com.google.gson.Gson;
import java.util.Scanner;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.ArrayList;
import java.util.Optional;

/** Patent Search agent that seraches contextually matching patents for user search request. */
public class App {
  static FunctionTool getQueryTool = FunctionTool.create(App.class, "getQuery");
  
  private static final String APP_NAME ="retailsearch-app";
  private static final String USER_ID = "user_12345";
  private static final String SESSION_ID = "session_1234567";
  private static final Logger logger = Logger.getLogger(App.class.getName());
  public static BaseAgent ROOT_AGENT = initAgent();

  static String RETAIL_SEARCH_NLQ_ENDPOINT = "https://retail-nlq-<<PROJECT_NUMBER>>.us-central1.run.app"; //Make sure you have the Cloud Run Function deployed for this.
  static String RETAIL_SEARCH_NLQ_RUN_ENDPOINT = "https://retail-nlq-exec-<<PROJECT_NUMBER>>.us-central1.run.app";


  public static BaseAgent initAgent() {
    return LlmAgent.builder()
          .name("retail-search-agent")
          .description("Retail Search agent")
          .model("gemini-2.0-flash-001")
          .instruction(
              """
              You are a helpful retail search assistant for apparels of 2 categories: clothing and footwear. 
              There are many subcategories in both and your job is check the question that the user has and 
              always return a useful response to the user. If the query fails also, do not respond with abrupt cutoff.
              Respond with a graceful and meaningful message asking them to search again narrowing or broadening their context as applicable.
             Use the tool `get_query` to take care of the following:
              1. Get the  query corresponding to the user search.
              2. Execute the query and get the response. 
            You will then process the response in a way it is formatted for the user question and respond to the user. 
            The matching apparels of the latest and older user searches are all in the context object `apparels`. 
            Use the information from user query results for answering any follow up questions that the user may have.
            Do not assume every search is a tool call. Assess the query to understand if you have any context form the user's past queries.
            Use the tool when appropriate and don't make stuff up on your own.
            """)
          .tools(getQueryTool)
          .outputKey("apparels")
          .build();
}
    public static void main(String[] args) throws Exception  {
      InMemoryRunner runner = new InMemoryRunner(ROOT_AGENT);
      Map<String, Object> initialState = new HashMap<>();
      initialState.put("apparels", "");
      Session session =
          runner
              .sessionService()
              .createSession(runner.appName(), USER_ID )
              .blockingGet();
      logger.log(Level.INFO, () -> String.format("Initial session state: %s", session.state()));
 
      try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
        while (true) {
          System.out.print("\nYou > ");
          String userInput = scanner.nextLine();

          if ("quit".equalsIgnoreCase(userInput)) {
            break;
          }
          
          Content userMsg = Content.fromParts(Part.fromText(userInput));
          Flowable<Event> events = 
                  runner.runAsync(session.userId(), session.id(), userMsg);

          System.out.print("\nAgent > ");
          events.blockingForEach(event -> 
                  System.out.print(event.stringifyContent()));
      }
    }
  }

  

  // --- Define the Tool ---
  /*  1. Gets the  query corresponding to the user search.
  2. Executes the query and returns the retail matches from the database. */
  public static Map<String, String> getQuery(
    @Schema(description = "The search text for which the user wants to find retail matches or answers from the database") 
    String searchText) {
      try{
        String querystring = querySearch(searchText, RETAIL_SEARCH_NLQ_ENDPOINT);
        String apparels = querySearch(querystring, RETAIL_SEARCH_NLQ_RUN_ENDPOINT);
       /* String previousResults = (String) ctx.session().state().get("apparels");
        if(!previousResults.isEmpty() && previousResults!=null){
         apparels = apparels + " Older Results: " + previousResults;
        } */
        
        return Map.of(
          "status", "success",
          "report", apparels
        );
      }catch(Exception e){
        return Map.of(
          "status", "error",
          "report", "None matched your search!!"
        );
      }
}

public static String querySearch(String searchText, String endpoint) throws Exception{
  String queryString = "";
   try{
      URL url = new URL(endpoint);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setDoOutput(true);

      // Create JSON payload
      Gson gson = new Gson();
      Map<String, String> data = new HashMap<>();
      data.put("search", searchText);
      String jsonInputString = gson.toJson(data);
      
      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = jsonInputString.getBytes("utf-8");
        os.write(input, 0, input.length);
      }
      int responseCode = conn.getResponseCode();

      if (responseCode == HttpURLConnection.HTTP_OK) {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();

        while ((inputLine = in.readLine()) != null) {
          response.append(inputLine);
        }
        in.close();
        queryString = response.toString();
        System.out.println("POST request worked! " + queryString);
      } else {
        System.out.println("POST request did not work!");
      }
   } catch (Exception e) {
    System.out.println(e);
  }
  return queryString;
 }
}