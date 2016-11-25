package de.appsist.service.ps;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.platform.Verticle;

import de.appsist.commons.misc.StatusSignalSender;

/**
 * Main verticle for the performance support service.
 * @author simon.schwantzer(at)im-c.de  
 */
public class MainVerticle extends Verticle {
	private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
	public static final String SERVICE_ID = "psd";
	
	private ModuleConfiguration config;
	private ConnectorRegistry connectors;
	private HandlerRegistry handlers;
	private StatusSignalSender statusSignalSender;
	
	@Override
	public void start() {
		try {
			config = new ModuleConfiguration(container.config());
		} catch (IllegalArgumentException e) {
			logger.warn("Missing or invalid configuration: " + e.getMessage() + "\nAborting!");
			System.exit(1);
		}
		
		// Deploy db on demand. 
		if (config.deployDb()) {
			container.deployModule("io.vertx~mod-mongo-persistor~2.1.0", config.getDBConfiguration(), new Handler<AsyncResult<String>>() {
				@Override
				public void handle(AsyncResult<String> result) {
					if (result.succeeded()) {
						logger.debug("Deployed Mongo DB connector.");
					} else {
						logger.warn("Failed to deploy Mongo DB connector.", result.cause());
					}
				}
			});
		}
		
		connectors = new ConnectorRegistry(vertx, config);
		handlers = new HandlerRegistry(vertx, connectors, config);
		handlers.init();
		
		statusSignalSender = new StatusSignalSender("performance-support", vertx, config.getStatusSignalConfig());
		statusSignalSender.start();
		
		logger.debug("APPsist service \"Performance Support\" has been initialized with the following configuration:\n" + config.asJson().encodePrettily());
	}
	
	@Override
	public void stop() {
		statusSignalSender.stop();
		logger.debug("APPsist service \"Performance Support\" has been stopped.");
	}
}
