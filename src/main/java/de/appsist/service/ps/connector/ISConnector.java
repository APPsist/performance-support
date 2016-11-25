package de.appsist.service.ps.connector;

import java.util.HashMap;
import java.util.Map;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.service.pki.connector.HttpException;

public class ISConnector {
	private static final Logger logger = LoggerFactory.getLogger(ISConnector.class);
	private final HttpClient isClient;
	private final String basePath;
	private final Map<String, String> contentReferenceCache;
	
	public ISConnector(Vertx vertx, JsonObject serviceConfig) {
		isClient = vertx.createHttpClient();
		isClient.setHost(serviceConfig.getString("host"));
		isClient.setPort(serviceConfig.getInteger("port"));
		isClient.setSSL(serviceConfig.getBoolean("secure", false));
		basePath = serviceConfig.getObject("paths").getString("ihs");
		contentReferenceCache = new HashMap<>();
	}
	
	/*
	public void getContentForTask(String userId, String processId, String processInstanceId, String elementId, final AsyncResultHandler<JsonObject> resultHandler) {
		resultHandler.handle(new AsyncResult<JsonObject>() {
			@Override
			public boolean succeeded() {
				return true;
			}
			
			@Override
			public JsonObject result() {
				JsonObject dummy = new JsonObject();
				dummy.putString("contentId", "default");
				return dummy;
			}
			
			@Override
			public boolean failed() {
				return false;
			}
			
			@Override
			public Throwable cause() {
				return null;
			}
		});
	}
	*/
	
	public void getContentForTask(String userId, String rootProcessId, String processId, String elementId, final AsyncResultHandler<JsonObject> resultHandler) {
		StringBuilder path = new StringBuilder();
		path.append(basePath).append("/contentForTask")
			.append("?measureId=").append(rootProcessId)
			.append("&processId=").append(processId)
			.append("&elementId=").append(elementId)
			.append("&userId=").append(userId);
		isClient.get(path.toString(), new Handler<HttpClientResponse>() {
			
			@Override
			public void handle(final HttpClientResponse response) {
				response.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(final Buffer buffer) {
						
						resultHandler.handle(new AsyncResult<JsonObject>() {
							@Override
							public boolean succeeded() {
								return response.statusCode() == 200;
							}
							
							@Override
							public JsonObject result() {
								return succeeded() ? new JsonObject(buffer.toString()) : null;
							}
							
							@Override
							public boolean failed() {
								return !succeeded();
							}
							
							@Override
							public Throwable cause() {
								return failed() ? new HttpException(buffer.toString(), response.statusCode()) : null;
							}
						});
					}
				});
			}
		}).end();
	}
	
	public void getContentForCallActivity(String userId, String rootProcessId, String processId, String activityProcessId, final AsyncResultHandler<JsonObject> resultHandler) {
		StringBuilder path = new StringBuilder();
		path.append(basePath).append("/contentForActivity")
			.append("?measureId=").append(rootProcessId)
			.append("&processId=").append(processId)
			.append("&calledProcess=").append(activityProcessId)
			.append("&userId=").append(userId);
		logger.debug("Requesting content for call activity: " + path.toString());
		isClient.get(path.toString(), new Handler<HttpClientResponse>() {
			
			@Override
			public void handle(final HttpClientResponse response) {
				response.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(final Buffer buffer) {
						logger.debug("Received response for call activity content request: " + buffer.toString());
						resultHandler.handle(new AsyncResult<JsonObject>() {
							@Override
							public boolean succeeded() {
								return response.statusCode() == 200;
							}
							
							@Override
							public JsonObject result() {
								return succeeded() ? new JsonObject(buffer.toString()) : null;
							}
							
							@Override
							public boolean failed() {
								return !succeeded();
							}
							
							@Override
							public Throwable cause() {
								return failed() ? new HttpException(buffer.toString(), response.statusCode()) : null;
							}
						});
					}
				});
			}
		}).end();
	}
	
	public void getAdditionalContent(final String userId, final String rootProcessId, final String processId, final String elementId, final AsyncResultHandler<JsonObject> resultHandler) {
		StringBuilder path = new StringBuilder();
		path.append(basePath).append("/additionalContent")
			.append("?measureId=").append(rootProcessId)
			.append("&processId=").append(processId)
			.append("&elementId=").append(elementId)
			.append("&userId=").append(userId);
		logger.debug("Requesting additional content: " + path.toString());
		isClient.get(path.toString(), new Handler<HttpClientResponse>() {
			
			@Override
			public void handle(final HttpClientResponse response) {
				response.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(final Buffer buffer) {
						logger.debug("Received response for additional content request: " + buffer.toString());
						resultHandler.handle(new AsyncResult<JsonObject>() {
							@Override
							public boolean succeeded() {
								return response.statusCode() == 200;
							}
							
							@Override
							public JsonObject result() {
								JsonObject result;
								if (succeeded()) {
									result = new JsonObject(buffer.toString());
									String contentId = result.getString("contentId");
									if (contentId != null) {
										addReferenceToCache(processId, elementId, userId, contentId);
									} else {
										String cachedContentId = retrieveReferenceFromCache(processId, elementId, userId);
										if (cachedContentId != null) {
											logger.debug("Using cached content: " + cachedContentId);
											result.putString("contentId", cachedContentId);
										}
									}
								} else {
									result = null;
								}
								return result;
							}
							
							@Override
							public boolean failed() {
								return !succeeded();
							}
							
							@Override
							public Throwable cause() {
								return failed() ? new HttpException(buffer.toString(), response.statusCode()) : null;
							}
						});
					}
				});
			}
		}).end();
	}
	
	
	private void addReferenceToCache(String processId, String elementId, String userId, String contentId) {
		String key = generateCacheKey(processId, elementId, userId);
		contentReferenceCache.put(key, contentId);
	}
	
	private String retrieveReferenceFromCache(String processId, String elementId, String userId) {
		String key = generateCacheKey(processId, elementId, userId);
		return contentReferenceCache.get(key);
	}
	
	private String generateCacheKey(String processId, String elementId, String userId) {
		return new StringBuilder()
				.append(processId).append("|")
				.append(elementId).append("|")
				.append(userId)
				.toString();
	}
}
