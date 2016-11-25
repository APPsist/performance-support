package de.appsist.service.ps.connector;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

/**
 * Connector for the learning experience service.
 * @author simon.schwantzer(at)im-c.de
 */
public class BMDConnector {
	private final static String SERVICE_ID = "appsist:service:usermodel";
	
	private final EventBus eventBus;
	
	public BMDConnector(EventBus eventBus) {
		this.eventBus = eventBus;
	}
	/*
	public int getNumberOfCompletedExecutions(String processId, String userId, String token) {
		Random random = new Random();
		return random.nextInt(100);
	}*/
	
	public void isExperienced(String sessionId, String processId, String userId, String token, final AsyncResultHandler<Boolean> resultHandler) {
		String address = SERVICE_ID + "#mastersProcess"; 
		JsonObject request = new JsonObject()
			.putString("sessionId", sessionId)
			.putString("userId", userId)
			.putString("token", token)
			.putString("processId", processId);
		eventBus.send(address, request, new Handler<Message<JsonObject>>() {
			
			@Override
			public void handle(Message<JsonObject> message) {
				final JsonObject body = message.body();
				final Boolean mastered = body.getBoolean("mastered");
				
				resultHandler.handle(new AsyncResult<Boolean>() {
					
					@Override
					public boolean succeeded() {
						return mastered != null;
					}
					
					@Override
					public Boolean result() {
						return mastered;
					}
					
					@Override
					public boolean failed() {
						return !succeeded();
					}
					
					@Override
					public Throwable cause() {
						return failed() ? new Throwable("Failed to retrieve user experience on task. Response: " + body.encode()) : null;
					}
				});
			}
		});
	}
}
