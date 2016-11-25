package de.appsist.service.ps.connector;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.core.logging.Logger;

import de.appsist.service.iid.server.model.Popup;
import de.appsist.service.pki.connector.HttpException;

public class KKDConnector {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(KKDConnector.class);
	public static final String SERVICE_ID = "appsist:service:kkd";
	
	private final EventBus eventBus;
	
	public KKDConnector(EventBus eventBus) {
		this.eventBus = eventBus;
	}
	
	public void getContactPopup(String sessionId, String token, String processId, final AsyncResultHandler<Popup> resultHandler) {
		JsonObject request = new JsonObject()
			.putString("sessionId", sessionId)
			.putString("token", token)
			.putString("processId", processId);
		eventBus.send(SERVICE_ID + "#getContactPopup", request, new Handler<Message<JsonObject>>() {

			@Override
			public void handle(Message<JsonObject> message) {
				final JsonObject body = message.body();
				resultHandler.handle(new AsyncResult<Popup>() {
					
					@Override
					public boolean succeeded() {
						return "ok".equals(body.getString("status"));
					}
					
					@Override
					public Popup result() {
						return succeeded() ? new Popup(body.getObject("popup")) : null;
					}
					
					@Override
					public boolean failed() {
						return !succeeded();
					}
					
					@Override
					public Throwable cause() {
						return failed() ? new HttpException(body.getString("message"), body.getInteger("code")) : null;
					}
				});
			}
		});
	}

}
