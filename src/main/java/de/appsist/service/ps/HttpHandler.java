package de.appsist.service.ps;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.HttpServerResponse;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.service.auth.connector.model.Session;
import de.appsist.service.iid.server.model.ContentBody;
import de.appsist.service.iid.server.model.HttpPostAction;
import de.appsist.service.iid.server.model.InstructionItemBuilder;
import de.appsist.service.iid.server.model.Popup;
import de.appsist.service.iid.server.model.PopupBuilder;
import de.appsist.service.iid.server.model.ServiceItem;

public class HttpHandler {
	private static final Logger logger = LoggerFactory.getLogger(HttpHandler.class);
	
	private final ConnectorRegistry connectors;
	private final HandlerRegistry handlers;
	
	public HttpHandler(HandlerRegistry handlers, int port, String basePath, boolean isDebugMode) {
		this.handlers = handlers;
		this.connectors = handlers.connectors();
		RouteMatcher routeMatcher = initRouteMatcher(basePath, isDebugMode);
		
		handlers.vertx().createHttpServer()
			.requestHandler(routeMatcher)
			.listen(port);
	}
	
	private RouteMatcher initRouteMatcher(String basePath, boolean isDebugMode) {
		RouteMatcher routeMatcher = new BasePathRouteMatcher(basePath);
		
		routeMatcher.post("/startSupport/:supportId", new Handler<HttpServerRequest>() {
			@Override
			public void handle(final HttpServerRequest request) {
				final HttpServerResponse response = request.response();
				final String supportId = request.params().get("supportId");
				request.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						logger.debug("Start assistance: " + body.encodePrettily());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						JsonObject context = body.getObject("context", new JsonObject());
						handleStartSupportRequest(supportId, sessionId, token, context, response);
					}
				});
			}
		});
		
		routeMatcher.post("/showContacts", new Handler<HttpServerRequest>() {
			
			@Override
			public void handle(HttpServerRequest request) {
				final HttpServerResponse response = request.response();
				request.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						handleShowContacts(sessionId, token, response);
					}
				});
			}
		});
		
		routeMatcher.post("/showAdditionalContent", new Handler<HttpServerRequest>() {
			
			@Override
			public void handle(HttpServerRequest request) {
				final HttpServerResponse response = request.response();
				request.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String contentId = body.getString("contentId");
						handleShowAdditionalContent(response, sessionId, contentId);
					}
				});
			}
		});
		
		routeMatcher.post("/navigate/confirm", new Handler<HttpServerRequest>() {
			
			@Override
			public void handle(final HttpServerRequest request) {
				request.bodyHandler(new Handler<Buffer>() {
					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						handlers.userInteractionHandler().setClientToken(sessionId, token);
						handlers.userInteractionHandler().handleConfirmRequest(request.response(), sessionId, body.getString("processId"));
					}
				});
			}
		});
		
		routeMatcher.post("/navigate/next", new Handler<HttpServerRequest>() {
			@Override
			public void handle(final HttpServerRequest request) {
				final String elementId = request.params().get("elementId");
				request.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						handlers.userInteractionHandler().setClientToken(sessionId, token);
						handlers.userInteractionHandler().handleNextRequest(request.response(), sessionId, elementId);
					}
				});
			}
		});
		
		routeMatcher.post("/navigate/previous", new Handler<HttpServerRequest>() {
			@Override
			public void handle(final HttpServerRequest request) {
				request.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						Integer index = body.getInteger("index");
						handlers.userInteractionHandler().setClientToken(sessionId, token);
						handlers.userInteractionHandler().handlePreviousRequest(request.response(), sessionId, index);
					}
				});
			}
		});
		
		routeMatcher.post("/navigate/details", new Handler<HttpServerRequest>() {

			@Override
			public void handle(final HttpServerRequest request) {
				request.bodyHandler(new Handler<Buffer>() {

					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						handlers.userInteractionHandler().setClientToken(sessionId, token);
						String activityProcessId = body.getString("activityProcessId");
						handlers.userInteractionHandler().handleDetailsRequest(request.response(), sessionId, token, activityProcessId);
					}
				});
			}
			
		});
		
		routeMatcher.post("/navigate/close", new Handler<HttpServerRequest>() {

			@Override
			public void handle(final HttpServerRequest request) {
				request.bodyHandler(new Handler<Buffer>() {

					@Override
					public void handle(Buffer buffer) {
						JsonObject body = new JsonObject(buffer.toString());
						String sessionId = body.getString("sessionId");
						String token = body.getString("token");
						handlers.userInteractionHandler().setClientToken(sessionId, token);
						handlers.userInteractionHandler().handleCloseRequest(request.response(), sessionId, token);
					}
				});
			}
		});
		
		if (isDebugMode) {
			routeMatcher.post("/debug/addServiceItem", new Handler<HttpServerRequest>() {
				
				@Override
				public void handle(HttpServerRequest request) {
					final HttpServerResponse response = request.response();
					request.bodyHandler(new Handler<Buffer>() {
						
						@Override
						public void handle(Buffer buffer) {
							JsonObject body = new JsonObject(buffer.toString());
							String sessionId = body.getString("sessionId");
							String processId = body.getString("processId");
							String title = body.getString("title");
							
							List<ServiceItem> serviceItems = new ArrayList<>(); 
							HttpPostAction action = new HttpPostAction("http://localhost:8080/services/psd/startSupport/" + processId, new JsonObject());
							serviceItems.add(new InstructionItemBuilder().setId(UUID.randomUUID().toString()).setPriority(50).setService("psd").setTitle(title).setAction(action).build());
							
							
							connectors.iidConnector().addServiceItems(sessionId, serviceItems, new AsyncResultHandler<Void>() {
								
								@Override
								public void handle(AsyncResult<Void> addRequest) {
									if (addRequest.succeeded()) {
										response.end();
									} else {
										response.setStatusCode(500).end(addRequest.cause().getMessage());
									}
								}
							}); 
						}
					});
					
				}
			});
		}
		
		return routeMatcher;
	}
	
	private void handleStartSupportRequest(final String supportId, final String sessionId, final String token, final JsonObject context, final HttpServerResponse response) {
		connectors.authConnector().getSession(sessionId, token, new AsyncResultHandler<Session>() {
			
			@Override
			public void handle(AsyncResult<Session> event) {
				if (event.succeeded()) {
					Session session = event.result();
					LocalSession localSession = new LocalSession(session.getId(), session.getUserId());
					localSession.setToken(token);
					handlers.userInteractionHandler().handleStartSupportRequest(response, supportId, context, localSession);
				} else {
					logger.warn("Failed to retrieve user session.", event.cause());
					response.setStatusCode(500).end(event.cause().getMessage());
				}
			}
		});
	}
	
	private void handleShowContacts(String sessionId, String token, HttpServerResponse response) {
		LocalSession session = handlers.userInteractionHandler().getLocalSession(sessionId);
		if (session != null ) {
			Popup contactsPopup = session.getContactsPopup();
			if (contactsPopup != null) {
				connectors.iidConnector().displayPopup(sessionId, null, MainVerticle.SERVICE_ID, contactsPopup, new AsyncResultHandler<Void>() {
					
					@Override
					public void handle(AsyncResult<Void> event) {
						if (event.failed()) {
							logger.warn("Failed to display contacts popup", event.cause());
						}
					}
				});
			}			
		}
		response.end();
	}
	
	private void handleShowAdditionalContent(final HttpServerResponse response, String sessionId, String contentId) {
		final LocalSession session = handlers.userInteractionHandler().getLocalSession(sessionId);
		if (session == null) {
			response.setStatusCode(400).end("Unknown session id.");
			return;
		}
		if (contentId == null || contentId.isEmpty()) {
			response.setStatusCode(400).end("Missing or empty content id.");
			return;
		}
		PopupBuilder builder = new PopupBuilder();
		builder.setTitle("Weiterführender Inhalt");
		builder.setBody(new ContentBody.Package(contentId));
		Popup popup = builder.build();
		connectors.iidConnector().displayPopup(sessionId, null, MainVerticle.SERVICE_ID, popup, new AsyncResultHandler<Void>() {
			
			@Override
			public void handle(AsyncResult<Void> event) {
				if (event.failed()) {
					logger.warn("Failed to display additional content popup.", event.cause());
				}
				
			}
		});
	}
}
