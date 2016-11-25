package de.appsist.service.ps.connector;

import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.json.JsonObject;

/**
 * Connector for the content navigation service.
 * @author simon.schwantzer(at)im-c.de
 *
 */
public class CNSConnector {
	private final EventBus eventBus;
	
	public CNSConnector(EventBus eventBus) {
		this.eventBus = eventBus;
	}
	
	public void publishContentSeenEvent(String sessionId, String token, String contentId) {
		JsonObject message = new JsonObject()
			.putString("sessionId", sessionId)
			.putString("token", token)
			.putString("contentId", contentId);
		eventBus.send("appsist:content:contentSeen", message);
	}
}
