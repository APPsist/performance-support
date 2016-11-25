package de.appsist.service.ps;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

public class HandlerRegistry {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(LoggerFactory.class);
	
	private final Vertx vertx;
	private final ConnectorRegistry connectors;
	private final ModuleConfiguration config;
	private UserInteractionHandler userInteractionHandler;
	private HttpHandler httpHandler;
	
	public HandlerRegistry(Vertx vertx, ConnectorRegistry connectors, ModuleConfiguration config) {
		this.vertx = vertx;
		this.config = config;
		this.connectors = connectors;
	}
	
	public Vertx vertx() {
		return vertx;
	}
	
	public EventBus eventBus() {
		return vertx.eventBus();
	}
	
	public ConnectorRegistry connectors() {
		return connectors;
	}
	
	public void init() {
		StringBuilder builder = new StringBuilder(300);
		builder.append("http://localhost:").append(config.getPort()).append(config.getBasePath());
		String baseUrl = builder.toString();
		this.userInteractionHandler = new UserInteractionHandler(connectors, baseUrl);
		this.httpHandler = new HttpHandler(this, config.getPort(), config.getBasePath(), config.isDebugMode());
	}
	
	public UserInteractionHandler userInteractionHandler() {
		return userInteractionHandler;
	}
	
	public HttpHandler httpHandler() {
		return httpHandler;
	}

}
