package de.appsist.service.ps;

import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.misc.StatusSignalConfiguration;

public class ModuleConfiguration {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(ModuleConfiguration.class);
	
	private final JsonObject json;
	private final StatusSignalConfiguration statusSignalConfig;
	
	public ModuleConfiguration(JsonObject json) throws IllegalArgumentException {
		JsonObject webserver = json.getObject("webserver");
		if (webserver == null) {
			throw new IllegalArgumentException("Missing [webserver] object.");
		}
		if (webserver.getString("basePath") == null) {
			throw new IllegalArgumentException("Missing [webserver.basePath] string.");
		}
		if (webserver.getNumber("port") == null) {
			throw new IllegalArgumentException("Missing [webserver.port] int.");
		}
		
		if (json.getObject("services") == null) {
			throw new IllegalArgumentException("Missing [services] object.");
		}
		
		if (!json.containsField("db")) {
			throw new IllegalArgumentException("Missing either object or string [db].");
		}
		
		JsonObject statusSignalObject = json.getObject("statusSignal");
		statusSignalConfig = statusSignalObject != null ? new StatusSignalConfiguration(statusSignalObject) : new StatusSignalConfiguration();
		this.json = json;
	}

	public String getBasePath() {
		JsonObject webserver = json.getObject("webserver");
		return webserver.getString("basePath");
	}
	
	public int getPort() {
		JsonObject webserver = json.getObject("webserver");
		return webserver.getInteger("port");
	}
	
	public JsonObject getServicesConfiguration() {
		return json.getObject("services");
	}
	
	public boolean deployDb() {
		return json.getValue("db") instanceof JsonObject;
	}
	
	public JsonObject getDBConfiguration() {
		return json.getObject("db");
	}
	
	public String getDBAddress() {
		return json.getString("db");
	}
	
	public JsonObject asJson() {
		return json;
	}
	
	public boolean isDebugMode() {
		return json.getBoolean("debugMode", false);
	}
	
	public StatusSignalConfiguration getStatusSignalConfig() {
		return statusSignalConfig;
	}
}
