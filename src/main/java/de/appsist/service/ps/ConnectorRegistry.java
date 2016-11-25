package de.appsist.service.ps;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.json.JsonObject;

import de.appsist.service.auth.connector.AuthServiceConnector;
import de.appsist.service.iid.server.connector.IIDConnector;
import de.appsist.service.pki.connector.PKIConnector;
import de.appsist.service.ps.connector.BMDConnector;
import de.appsist.service.ps.connector.CNSConnector;
import de.appsist.service.ps.connector.ISConnector;
import de.appsist.service.ps.connector.KKDConnector;

public class ConnectorRegistry {
	private final BMDConnector bmdConnector;
	private final CNSConnector cnsConnector;
	private final ISConnector isConnector;
	private final KKDConnector kkdConnector;
	private final AuthServiceConnector authConnector;
	private final IIDConnector iidConnector;
	private final PKIConnector pkiConnector;
	
	
	public ConnectorRegistry(Vertx vertx, ModuleConfiguration config) {
		EventBus eventBus = vertx.eventBus();
		JsonObject servicesConfig = config.getServicesConfiguration();
		bmdConnector = new BMDConnector(eventBus);
		cnsConnector = new CNSConnector(eventBus);
		isConnector = new ISConnector(vertx, config.getServicesConfiguration());
		kkdConnector = new KKDConnector(eventBus);
		authConnector = new AuthServiceConnector(eventBus, AuthServiceConnector.SERVICE_ID);
		iidConnector = new IIDConnector(eventBus, IIDConnector.DEFAULT_ADDRESS);
		pkiConnector = new PKIConnector(vertx, servicesConfig.getString("host"), servicesConfig.getInteger("port"), servicesConfig.getBoolean("secure"), servicesConfig.getObject("paths").getString("pki"));
	}
	
	public BMDConnector bmdConnector() {
		return bmdConnector;
	}
	
	public CNSConnector cnsConnector() {
		return cnsConnector;
	}
	
	public ISConnector isConnector() {
		return isConnector;
	}
	
	public KKDConnector kkdConnector() {
		return kkdConnector;
	}
	
	public AuthServiceConnector authConnector() {
		return authConnector;
	}
	
	public IIDConnector iidConnector() {
		return iidConnector;
	}
	
	public PKIConnector pkiConnector() {
		return pkiConnector;
	}
}
