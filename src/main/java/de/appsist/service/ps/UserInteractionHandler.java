package de.appsist.service.ps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.http.HttpServerResponse;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import com.github.jknack.handlebars.io.TemplateLoader;

import de.appsist.commons.event.CallActivityEvent;
import de.appsist.commons.event.ProcessCompleteEvent;
import de.appsist.commons.event.ProcessErrorEvent;
import de.appsist.commons.event.ProcessTerminateEvent;
import de.appsist.commons.event.ProcessUserRequestEvent;
import de.appsist.commons.event.ServiceTaskEvent;
import de.appsist.commons.event.TaskEvent;
import de.appsist.service.iid.server.connector.IIDConnector;
import de.appsist.service.iid.server.model.Action;
import de.appsist.service.iid.server.model.Activity;
import de.appsist.service.iid.server.model.AssistanceStep;
import de.appsist.service.iid.server.model.AssistanceStepBuilder;
import de.appsist.service.iid.server.model.ContentBody;
import de.appsist.service.iid.server.model.HttpPostAction;
import de.appsist.service.iid.server.model.Popup;
import de.appsist.service.iid.server.model.PopupBuilder;
import de.appsist.service.iid.server.model.SendMessageAction;
import de.appsist.service.pki.connector.HttpException;
import de.appsist.service.pki.event.ProcessAutomatedFlowEvent;
import de.appsist.service.pki.event.ProcessAutomatedFlowEvent.Condition;
import de.appsist.service.pki.model.ProcessDefinition;
import de.appsist.service.pki.model.ProcessElementInstance;
import de.appsist.service.pki.model.ProcessElementType;
import de.appsist.service.pki.model.ProcessInstance;

/**
 * Handler for user client requests for both HTTP and event bus. 
 * @author simon.schwantzer(at)im-c.de
 *
 */
public class UserInteractionHandler {
	private static final Logger logger = LoggerFactory.getLogger(UserInteractionHandler.class);
	
	private final ConnectorRegistry connectors;
	private final String baseUrl;
	private final Map<String, Template> templates; // Map with handlebars templates for HTML responses.
	private final Map<String, LocalSession> sessions; // <sessionId, Session>
	
	public UserInteractionHandler(ConnectorRegistry connectors, String baseUrl) {
		this.baseUrl = baseUrl;
		this.connectors = connectors;
		sessions = new HashMap<>();
		templates = new HashMap<>();
		
		try {
			TemplateLoader loader = new ClassPathTemplateLoader();
			loader.setPrefix("/templates");
			loader.setSuffix(".html");
			Handlebars handlebars = new Handlebars(loader);
			templates.put("standby", handlebars.compile("standby"));
			templates.put("processOverview", handlebars.compile("processOverview"));
			templates.put("processComplete", handlebars.compile("processComplete"));
			templates.put("processError", handlebars.compile("processError"));
			templates.put("error", handlebars.compile("error"));
		} catch (IOException e) {
			logger.fatal("Failed to load templates.", e);
		}
		registerPkiEvents();
	}
	
	public LocalSession getLocalSession(String sessionId) {
		return sessions.get(sessionId);
	}
	
	private void registerPkiEvents() {
		connectors.pkiConnector().registerTaskHandler(new Handler<TaskEvent>() {
			
			@Override
			public void handle(final TaskEvent event) {
				if (event.getModelId().equals(ServiceTaskEvent.MODEL_ID)) return; 
				final LocalSession session = sessions.get(event.getSessionId());
				if (session == null) {
					// We have no local session for this task. Aborting
					return;
				}
				final String processId = event.getProcessId();
				final String processInstanceId = event.getProcessInstanceId();
				final String rootProcessId = event.getRootProcessId(); 
				final ProcessElementInstance currentElement = connectors.pkiConnector().getCachedProcessElementInstance(processInstanceId, event.getElementId());
				
				session.setActiveElement(currentElement);
				double progress = event.getProgress();
				session.setProgress(progress);
				// SessionHistory sessionHistory = sessionHistories.get(sessionId);
				// sessionHistory.addStep(processInstanceId, processId, currentElement.getId(), currentElement.getType());
				retrieveAndUpdateContentForTask(
					session,
					currentElement,
					processId,
					processInstanceId,
					rootProcessId,
					progress
				);
			}
		});
		
		connectors.pkiConnector().registerProcessUserRequestHandler(new Handler<ProcessUserRequestEvent>() {
			@Override
			public void handle(final ProcessUserRequestEvent event) {
				final LocalSession session = sessions.get(event.getSessionId());
				if (session == null) {
					// We have no local session for this task. Aborting
					return;
				}
				ProcessElementInstance currentElement = session.getActiveElement();
				final AssistanceStepBuilder builder = new AssistanceStepBuilder();
				builder.setTitle(currentElement.getLabel());
				// session.getProcessDefinition().getString("label");
				Double progress = session.getProgress();
				builder.setProgress(progress != null ? progress : 0.0d);
				builder.setInfo(event.getMessage());
				builder.setContentBody(new ContentBody.Empty());
				
				int i = 0;
				for (Entry<String, String> entry : event.getOptions().entrySet()) {
					String display = entry.getValue();
					Action action = new HttpPostAction(baseUrl + "/navigate/next?elementId=" + entry.getKey(), new JsonObject());
					builder.addActionButtonWithText("select-" + i++, display, action);
				}
			
				try {
					AssistanceStep assistanceStep = builder.build();
					connectors.iidConnector().displayAssistance(session.getId(), MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
						
						@Override
						public void handle(AsyncResult<Void> event) {
							if (event.failed()) {
								logger.warn("Failed to propagte user request event.", event.cause());
							}
						}
					});
				} catch (IllegalArgumentException e) {
					logger.warn("Failed to propagte user request event.", e);
				}
			}
		});
		
		connectors.pkiConnector().registerProcessAutomatedFlowHandler(new Handler<ProcessAutomatedFlowEvent>() {
			
			@Override
			public void handle(ProcessAutomatedFlowEvent event) {
				final String processInstanceId = event.getProcessInstanceId();
				final String sessionId = event.getSessionId();
				final Condition condition = event.getCondition();
				final String defaultElement = event.getDefault();

				connectors.pkiConnector().getProcessInstance(processInstanceId, new AsyncResultHandler<ProcessInstance>() {
					
					@Override
					public void handle(AsyncResult<ProcessInstance> instanceRequest) {
						if (instanceRequest.succeeded()) {
							final ProcessInstance processInstance = instanceRequest.result();
							connectors.iidConnector().getUserActivity(sessionId, new AsyncResultHandler<Activity>() {

								@Override
								public void handle(AsyncResult<Activity> activityRequest) {
									if (activityRequest.succeeded()) {
										Activity activity = activityRequest.result();
										if (activity == Activity.SIDE && defaultElement != null) {
											logger.debug("Selected default flow as user is in side activity: " + defaultElement);
											connectors.pkiConnector().next(processInstanceId, sessionId, defaultElement, new AsyncResultHandler<ProcessElementInstance>() {
												
												@Override
												public void handle(AsyncResult<ProcessElementInstance> result) {
													if (!result.succeeded()) {
														logger.warn("Failed to continue process with automated flow.", result.cause());
													}
												}
											});
										} else {
											logger.debug("Context to decide automated flow: " + processInstance.getContext().encodePrettily());
											String nextElementId = condition.getElementForContext(processInstance.getContext().toMap());
											connectors.pkiConnector().next(processInstanceId, sessionId, nextElementId, new AsyncResultHandler<ProcessElementInstance>() {
												
												@Override
												public void handle(AsyncResult<ProcessElementInstance> result) {
													if (!result.succeeded()) {
														logger.warn("Failed to continue process with automated flow.", result.cause());
													}
												}
											});
										}
									} else {
										logger.warn("Failed to retrieve user activity to perform automated flow.", activityRequest.cause());
									}
								}
							});
						} else {
							logger.warn("Failed to retrieve process instance to perform automated flow.", instanceRequest.cause());
						}
					}
				});				
			}
		});
		
		connectors.pkiConnector().registerCallActivityHandler(new Handler<CallActivityEvent>() {
			@Override
			public void handle(final CallActivityEvent event) {
				final LocalSession session = sessions.get(event.getSessionId());
				if (session == null) {
					return;
				}
				// final String rootProcessId = event.get/RootProcessId();
				final String processInstanceId = event.getProcessInstanceId();
				final String processId = event.getProcessId();
				final String activityProcessId = event.getActivityProcessId();
				connectors.bmdConnector().isExperienced(session.getId(), event.getActivityProcessId(), session.getUserId(), session.getToken(), new AsyncResultHandler<Boolean>() {
					@Override
					public void handle(AsyncResult<Boolean> bmdRequest) {
						boolean isExperienced; 
						if (bmdRequest.succeeded()) {
							isExperienced = bmdRequest.result();
						} else {
							logger.warn(bmdRequest.cause().getMessage());
							isExperienced = false;
						}
						if (isExperienced) {
							ProcessElementInstance currentElement = connectors.pkiConnector().getCachedProcessElementInstance(event.getProcessInstanceId(), event.getElementId());
							session.setActiveElement(currentElement);
							double progress = event.getProgress();
							session.setProgress(progress);
							retrieveAndUpdateContentForActivity(session, currentElement, processId, processInstanceId, activityProcessId, event.getRootProcessId(), progress);
						} else {
							connectors.pkiConnector().confirm(event.getProcessInstanceId(), session.getId(), new AsyncResultHandler<ProcessInstance>() {
								
								@Override
								public void handle(AsyncResult<ProcessInstance> confirmRequest) {
									if (confirmRequest.succeeded()) {
										final ProcessInstance processInstance = confirmRequest.result();
										session.setActiveProcessInstance(processInstance);
										connectors.pkiConnector().getCurrentElement(processInstance.getId(), session.getId(), new AsyncResultHandler<ProcessElementInstance>() {
											@Override
											public void handle(AsyncResult<ProcessElementInstance> result) {
												if (result.succeeded()) {
													final ProcessElementInstance currentElement = result.result();
													session.setActiveElement(currentElement);
													double progress = event.getProgress();
													session.setProgress(progress);
													// retrieveAndUpdateContentForTask(session, currentElement, event.getActivityProcessId(), processInstance.getId(), rootProcessId, progress);
												} else {
													sendGenericErrorPage(session.getId(), (HttpException) result.cause());
												}
											}
										});
									} else {
										sendGenericErrorPage(session.getId(), (HttpException) confirmRequest.cause());
									}
								}
							});
						}
					}
				});
			}
		});
		
		connectors.pkiConnector().registerProcessCompleteHandler(new Handler<ProcessCompleteEvent>() {
			@Override
			public void handle(final ProcessCompleteEvent event) {
				final LocalSession session = sessions.get(event.getSessionId());
				if (session == null) {
					return;
				}
				ProcessInstance localProcessInstance = session.getActiveProcessInstance();
				if (localProcessInstance == null || !event.getProcessInstanceId().equals(localProcessInstance.getId())) {
					return;
				}
				final String parentInstanceId = event.getParentInstance();
				if (parentInstanceId != null) {
					// We are continue in the parent process.
					ProcessInstance processInstance = connectors.pkiConnector().getCachedProcessInstance(parentInstanceId);
					session.setActiveProcessInstance(processInstance);
					connectors.pkiConnector().next(parentInstanceId, session.getId(), null, new AsyncResultHandler<ProcessElementInstance>() {
						
						@Override
						public void handle(AsyncResult<ProcessElementInstance> nextRequest) {
							if (nextRequest.failed()) {
								logger.warn("Failed to continue parent process.", nextRequest.cause());
							}
						}
					});
				} else {
					// Top level process. Bring it to an end.
					ProcessDefinition processDefinition = connectors.pkiConnector().getCachedProcessDefinition(localProcessInstance.getProcessId());
					String title = processDefinition.getLabel();
					try {
						AssistanceStepBuilder builder = new AssistanceStepBuilder();
						builder.setTitle(title);
						builder.setProgress(1.0d);
						// builder.setInfo("Assistenz erfolgreich abgeschlossen.");
						// builder.setContentBody(new ContentBody.Empty());
						String path = new StringBuilder()
							.append("/services/ufs/feedbackForm?sid=").append(event.getSessionId())
							.append("&uid=").append(event.getUserId())
							.append("&pid=").append(event.getProcessId())
							.toString();
						
						builder.setContentBody(new ContentBody.Frame(path));
						// TODO Use content package instead of info text. 
						JsonObject messageBody = new JsonObject();
						messageBody.putString("action", "endDisplay");
						messageBody.putString("sessionId", session.getId());
						messageBody.putString("serviceId", MainVerticle.SERVICE_ID);
						Action closeAction = new SendMessageAction(IIDConnector.DEFAULT_ADDRESS, messageBody);
						builder.setCloseAction(closeAction);
						builder.addActionButtonWithText("close", "Schließen", closeAction);
						AssistanceStep assistanceStep = builder.build();
						connectors.iidConnector().displayAssistance(session.getId(), MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
							
							@Override
							public void handle(AsyncResult<Void> event) {
								if (event.failed()) {
									logger.warn("Failed to propagate process completion.", event.cause());
								}
							}
						});
						sessions.remove(session.getId());
					} catch (IllegalArgumentException e) {
						logger.warn("Failed to propagate process completion.", e);
					}
				}
			}
		});
		
		connectors.pkiConnector().registerProcessErrorHandler(new Handler<ProcessErrorEvent>() {
			@Override
			public void handle(final ProcessErrorEvent event) {
				final LocalSession session = sessions.get(event.getSessionId());
				if (session == null) {
					return;
				}
				ProcessInstance processInstance = session.getActiveProcessInstance();
				if (processInstance == null || !event.getProcessInstanceId().equals(processInstance.getId())) {
					return;
				}
				ProcessDefinition processDefinition = connectors.pkiConnector().getCachedProcessDefinition(processInstance.getProcessId()); 
				String title = processDefinition.getLabel();
				try {
					processDefinition.asJson().putString("errorMessage", event.getErrorMessage());
					processDefinition.asJson().putNumber("errorCode", event.getErrorCode());
					AssistanceStepBuilder builder = new AssistanceStepBuilder();
					builder.setTitle(title);
					builder.setProgress(1.0d);
					builder.setInfo("Es ist ein Fehler aufgetreten: " + event.getErrorMessage());
					builder.setContentBody(new ContentBody.Empty());
					JsonObject messageBody = new JsonObject();
					messageBody.putString("action", "endDisplay");
					messageBody.putString("sessionId", session.getId());
					messageBody.putString("serviceId", MainVerticle.SERVICE_ID);
					Action closeAction = new SendMessageAction(IIDConnector.DEFAULT_ADDRESS, messageBody);
					builder.setCloseAction(closeAction);
					// TODO Replace with real contact request.
					Action contactAction = new SendMessageAction("appsist:service:ccs", new JsonObject());
					builder.addActionButtonWithText("close", "Techniker kontaktieren", contactAction);
					
					AssistanceStep assistanceStep = builder.build();
					connectors.iidConnector().displayAssistance(session.getId(), MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
						
						@Override
						public void handle(AsyncResult<Void> event) {
							if (event.failed()) {
								logger.warn("Failed to propagate process error state.", event.cause());
							}
						}
					});
					sessions.remove(session.getId());
				} catch (IllegalArgumentException e) {
					logger.warn("Failed to propagate process error state.", e);
				}
			}
		});
		
		connectors.pkiConnector().registerProcessTerminateHandler(new Handler<ProcessTerminateEvent>() {
			@Override
			public void handle(final ProcessTerminateEvent event) {
				final LocalSession session = sessions.get(event.getSessionId());
				if (session == null) {
					return;
				}
				ProcessInstance processInstance = session.getActiveProcessInstance();
				if (processInstance == null || !event.getProcessInstanceId().equals(processInstance.getId())) {
					return;
				}
				ProcessDefinition processDefinition = connectors.pkiConnector().getCachedProcessDefinition(processInstance.getProcessId()); 
				String title = processDefinition.getLabel();
				try {
					AssistanceStepBuilder builder = new AssistanceStepBuilder();
					builder.setTitle(title);
					builder.setProgress(1.0d);
					builder.setInfo("Es steht keine weitere Assistenz für diesen Prozess zur Verfügung.");
					builder.setContentBody(new ContentBody.Empty());
					// TODO Replace with content package.
					JsonObject messageBody = new JsonObject();
					messageBody.putString("action", "endDisplay");
					messageBody.putString("sessionId", session.getId());
					messageBody.putString("serviceId", MainVerticle.SERVICE_ID);
					Action closeAction = new SendMessageAction(IIDConnector.DEFAULT_ADDRESS, messageBody);
					builder.setCloseAction(closeAction);
					
					AssistanceStep assistanceStep = builder.build();
					connectors.iidConnector().displayAssistance(session.getId(), MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
						
						@Override
						public void handle(AsyncResult<Void> event) {
							if (event.failed()) {
								logger.warn("Failed to propagate process termination.", event.cause());
							}
						}
					});
					sessions.remove(session.getId());
				} catch (IllegalArgumentException e) {
					logger.warn("Failed to propagate process error state.", e);
				}
			}
		});
	}
	
	private List<String> getProcessTitles(String processInstanceId) {
		List<String> processTitles = new ArrayList<>();
		Map<ProcessInstance, ProcessDefinition> processMap = connectors.pkiConnector().getProcessTree(processInstanceId);
		for (ProcessDefinition processDefinition : processMap.values()) {
			processTitles.add(processDefinition != null ? processDefinition.getLabel() : "[Unbekannter Prozess]");
		}
		return processTitles;
	}
	
	private void retrieveAndUpdateContentForTask(final LocalSession session, final ProcessElementInstance currentElement, final String processId, final String processInstanceId, final String rootProcessId, final double progress) {
		final String elementId = currentElement.getId();
		final String stepId = processId + "/" + elementId;
		connectors.isConnector().getContentForTask(session.getUserId(), rootProcessId, processId, elementId, new AsyncResultHandler<JsonObject>() {
			@Override
			public void handle(AsyncResult<JsonObject> result) {
				if (result.succeeded()) {
					String retrievedContentId = result.result().getString("contentId");
					if (retrievedContentId != null) {
						session.setContentForStep(stepId, retrievedContentId);
						logger.debug("Displaying content " + retrievedContentId + " for " + stepId + ".");
						buildAndSendContent(session, retrievedContentId, currentElement, processInstanceId, processId, null, rootProcessId, progress);
					} else {
						String cachedContentId = session.getCachedContent(stepId);
						if (cachedContentId != null) {
							logger.debug("Displaying content " + cachedContentId + " for " + stepId + ".");
							buildAndSendContent(session, cachedContentId, currentElement, processInstanceId, processId, null, rootProcessId, progress);
						} else {
							logger.warn("No content id retrieved found for " + stepId + "!");
							buildAndSendContent(session, null, currentElement, processInstanceId, processId, null, rootProcessId, progress);
						}
					}
				} else {
					HttpException exception = (HttpException) result.cause();
					sendGenericErrorPage(session.getId(), exception);
				}
			}
		});
	}
	
	/**
	 * 
	 * @param session Local session. 
	 * @param currentElement Call activity element.
	 * @param processId ID of the process calling the activity.
	 * @param processInstanceId ID of the process instance calling the activity.
	 * @param activityProcessId ID of the called process.
	 * @param rootProcessId ID of the root process.
	 * @param progress Progress to display.
	 */
	private void retrieveAndUpdateContentForActivity(final LocalSession session, final ProcessElementInstance currentElement, final String processId, final String processInstanceId, final String activityProcessId, final String rootProcessId, final double progress) {
		connectors.isConnector().getContentForCallActivity(session.getUserId(), rootProcessId, processId, activityProcessId, new AsyncResultHandler<JsonObject>() {
			@Override
			public void handle(AsyncResult<JsonObject> result) {
				if (result.succeeded()) {
					String contentId = result.result().getString("contentId") != null ? result.result().getString("contentId") : "404";
					if (contentId.equals("404")) {
						buildAndSendContent(session, null, currentElement, processInstanceId, processId, activityProcessId, rootProcessId, progress);
					} else {
						buildAndSendContent(session, contentId, currentElement, processInstanceId, processId, activityProcessId, rootProcessId, progress);
					}
				} else {
					HttpException exception = (HttpException) result.cause();
					sendGenericErrorPage(session.getId(), exception);
				}
			}
		});
	}
	
	private void buildAndSendContent(final LocalSession session, final String contentId, final ProcessElementInstance currentElement, final String processInstanceId, final String processId, String activityProcessId, final String rootProcessId, final double progress) {
		final AssistanceStepBuilder assistBuilder = new AssistanceStepBuilder();
		String title = currentElement.getLabel();
		assistBuilder.setProcessTitles(getProcessTitles(processInstanceId));
		assistBuilder.setTitle(title);
		assistBuilder.setProgress(progress);
		if (contentId != null) {
			assistBuilder.setContentBody(new ContentBody.Package(contentId));
		} else {
			assistBuilder.setContentBody(new ContentBody.Empty());
		}
		if (session.hasLastDisplay()) {
			Action previousAction = new HttpPostAction(baseUrl + "/navigate/previous", new JsonObject().putNumber("index", 1));
			assistBuilder.setBackAction(previousAction);
		}
		PopupBuilder popupBuilder = new PopupBuilder();
		popupBuilder.setTitle("Vorheriger Schritt");
		if (contentId != null) {
			popupBuilder.setBody(new ContentBody.Package(contentId));
		} else {
			popupBuilder.setBody(new ContentBody.HTML("Für den Schritt \"" + title + "\" ist keine Assistenz hinterlegt."));
		}
		session.setCurrentDisplay(popupBuilder.build());
		if (activityProcessId != null) {
			if (contentId == null) assistBuilder.setInfo("Für den Abschnitt ist keine Zusammenfassung hinterlegt. Klicken sie auf &quot;Anleitung anzeigen&quot; für weitere Informationen.");
			JsonObject body = new JsonObject().putString("activityProcessId", activityProcessId);
			Action detailsAction = new HttpPostAction(baseUrl + "/navigate/details", body);
			assistBuilder.addActionButtonWithText("details", "Anleitung anzeigen", detailsAction);
		} else {
			if (contentId == null) assistBuilder.setInfo("Für den aktuellen Schritt ist keine Assistenz hinterlegt.");
		}

		List<String> nextElements = currentElement.getNextElements();
		boolean hasNext = nextElements != null && nextElements.size() > 0;
		if (hasNext) {
			Action nextAction = new HttpPostAction(baseUrl + "/navigate/next", new JsonObject());
			// TODO Replace text with title from next process step.
			assistBuilder.addActionButtonWithText("next", "Bestätigen - Weiter", nextAction);
		}
		assistBuilder.setCloseAction(new HttpPostAction(baseUrl + "/navigate/close", new JsonObject()));
		
		/*
		connectors.kkdConnector().getContactPopup(session.getId(), session.getToken(), processId, new AsyncResultHandler<Popup>() {

			@Override
			public void handle(AsyncResult<Popup> kkdRequest) {
				if (kkdRequest.succeeded()) {
					session.setContactPopup(kkdRequest.result());
				} else {
					logger.warn("Failed to retrieve contact popup: " + kkdRequest.cause().getMessage());
				}
			}
		});
		assistBuilder.setContactsAction(new HttpPostAction(baseUrl + "/showContacts", new JsonObject()));
		*/
		
		connectors.isConnector().getAdditionalContent(session.getUserId(), rootProcessId, processId, currentElement.getId(), new AsyncResultHandler<JsonObject>() {
			
			@Override
			public void handle(AsyncResult<JsonObject> additionalContentRequest) {
				if (additionalContentRequest.succeeded()) {
					String contentId = additionalContentRequest.result().getString("contentId");
					if (contentId != null) {
						logger.debug("Received additional content for " + processId + "/" + currentElement.getId() + ": " + contentId);
						assistBuilder.setKnowledgeAction(new HttpPostAction(baseUrl + "/showAdditionalContent", new JsonObject().putString("contentId", contentId)));
						// DEBUG						
						// assistBuilder.setBackAction(new HttpPostAction(baseUrl + "/showAdditionalContent", new JsonObject().putString("contentId", contentId)));
						// END DEBUG
					} else {
						logger.debug("Received no additional content for " + processId + "/" + currentElement.getId() + ".");
					}
				} else {
					logger.warn("Failed to retrieve additional content for assistance step: " + additionalContentRequest.cause());
				}
				
				try {
					AssistanceStep assistanceStep = assistBuilder.build();
					connectors.iidConnector().displayAssistance(session.getId(), MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
						
						@Override
						public void handle(AsyncResult<Void> event) {
							if (event.succeeded()) {
								// Send event that content has been delivered.
								if (contentId != null) {
									connectors.cnsConnector().publishContentSeenEvent(session.getId(), session.getToken(), contentId);
								}
							} else {
								logger.warn("Failed to update content display.", event.cause());
							}
						}
					});
				} catch (IllegalArgumentException e) {
					logger.warn("Failed to update content display.", e);
				}
			}
		});
	}
	
	private void sendGenericErrorPage(String sessionId, HttpException exception) {
		AssistanceStepBuilder builder = new AssistanceStepBuilder();
		builder.setTitle("Allgemeiner Fehler");
		builder.setInfo("Es ist ein allgemeiner Fehler aufgetreten: " + exception.getMessage());
		builder.setContentBody(new ContentBody.Empty());
		JsonObject messageBody = new JsonObject();
		messageBody.putString("action", "endDisplay");
		messageBody.putString("sessionId", sessionId);
		messageBody.putString("serviceId", MainVerticle.SERVICE_ID);
		Action closeAction = new SendMessageAction(IIDConnector.DEFAULT_ADDRESS, messageBody);
		builder.setCloseAction(closeAction);
		builder.addActionButtonWithText("close", "Schließen", closeAction);
		builder.setProgress(1.0d);
		try {
			AssistanceStep assistanceStep = builder.build();
			connectors.iidConnector().displayAssistance(sessionId, MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
				
				@Override
				public void handle(AsyncResult<Void> event) {
					if (event.failed()) {
						logger.warn("Failed to display error page.", event.cause());
					}
				}
			});
		} catch (IllegalArgumentException e) {
			logger.warn("Failed to display error page.", e);
		}
	}
	
	private void sendMissingProcessErrorPage(String processId, String sessionId) {
		AssistanceStepBuilder builder = new AssistanceStepBuilder();
		builder.setTitle("Anleitung nicht gefunden");
		builder.setInfo("Leider konnte die Anleitung \"" + processId + "\" nicht gefunden werden. Bitte benachrichtigen Sie den Systemadministrator.");
		builder.setContentBody(new ContentBody.Empty());
		JsonObject messageBody = new JsonObject();
		messageBody.putString("action", "endDisplay");
		messageBody.putString("sessionId", sessionId);
		messageBody.putString("serviceId", MainVerticle.SERVICE_ID);
		Action closeAction = new SendMessageAction(IIDConnector.DEFAULT_ADDRESS, messageBody);
		builder.setCloseAction(closeAction);
		builder.addActionButtonWithText("close", "Schließen", closeAction);
		builder.setProgress(1.0d);
		try {
			AssistanceStep assistanceStep = builder.build();
			connectors.iidConnector().displayAssistance(sessionId, MainVerticle.SERVICE_ID, assistanceStep, new AsyncResultHandler<Void>() {
				
				@Override
				public void handle(AsyncResult<Void> event) {
					if (event.failed()) {
						logger.warn("Failed to display error page.", event.cause());
					}
				}
			});
		} catch (IllegalArgumentException e) {
			logger.warn("Failed to display error page.", e);
		}
	}
	
	public void handleStartSupportRequest(final HttpServerResponse response, final String processId, final JsonObject context, final LocalSession session) {
		if (sessions.containsKey(session.getId())) {
			logger.warn("Found existing local session. The old session will be overwritten.");
		}
		sessions.put(session.getId(), session);
		connectors.pkiConnector().getProcessDefinition(processId, new AsyncResultHandler<ProcessDefinition>() {
			
			@Override
			public void handle(AsyncResult<ProcessDefinition> processDefinitionRequest) {
				if (processDefinitionRequest.succeeded()) {
					connectors.pkiConnector().instantiateProcess(processId, session.getId(), session.getUserId(), context, new AsyncResultHandler<ProcessInstance>() {
						
						@Override
						public void handle(AsyncResult<ProcessInstance> result) {
							if (result.succeeded()) {
								ProcessInstance processInstance = result.result();
								JsonObject newContext = processInstance.getContext();
								for (String fieldName : context.getFieldNames()) {
									newContext.putValue(fieldName, context.getField(fieldName));
								}
								session.setActiveProcessInstance(processInstance);
								response.end();
							} else {
								logger.warn("Failed to update session: ", result.cause());
								response.setStatusCode(500).end("Failed to instantiate process.");
							}
						}
					});
				} else {
					HttpException exception = (HttpException) processDefinitionRequest.cause();
					switch (exception.getStatusCode()) {
					case 404:
						sendMissingProcessErrorPage(processId, session.getId());
						break;
					default:
						sendGenericErrorPage(session.getId(), exception);
					}
					response.setStatusCode(exception.getStatusCode());
					response.end(exception.getMessage());
					sessions.remove(session);
				}
			}
		});
	}
		
	public void handleConfirmRequest(final HttpServerResponse response, String sessionId, final String processId) {
		final LocalSession session = sessions.get(sessionId);
		if (session == null) {
			response.setStatusCode(400).end("Unknown session id.");
			return;
		}
		JsonObject context = session.getActiveProcessInstance() != null ? session.getActiveProcessInstance().getContext() : new JsonObject();
		connectors.pkiConnector().instantiateProcess(processId, session.getId(), session.getUserId(), context, new AsyncResultHandler<ProcessInstance>() {
			
			@Override
			public void handle(AsyncResult<ProcessInstance> result) {
				if (result.succeeded()) {
					ProcessInstance processInstance = result.result();
					session.setActiveProcessInstance(processInstance);
					response.end();
				} else {
					logger.warn("Failed to update session: ", result.cause());
					response.setStatusCode(500).end("Failed to instantiate process.");
				}
			}
		});
	}
	
	public void handleNextRequest(final HttpServerResponse response, String sessionId, final String elementId) {
		final LocalSession session = sessions.get(sessionId);
		if (session == null) {
			response.setStatusCode(400).end("Unknown session id.");
			return;
		}
		ProcessInstance processInstance = session.getActiveProcessInstance();
		ProcessElementInstance currentElement = session.getActiveElement();
		if (processInstance == null || currentElement == null) {
			response.setStatusCode(424);
			response.end("No support process running.");
			return;
		}
		List<String> nextElements = currentElement.getNextElements();
		if (nextElements.size() > 0) {
			connectors.pkiConnector().next(processInstance.getId(), session.getId(), elementId, new AsyncResultHandler<ProcessElementInstance>() {
				
				@Override
				public void handle(AsyncResult<ProcessElementInstance> result) {
					if (result.succeeded()) {
						// session.setCurrentElement(result.result());
						response.end();
					} else {
						HttpException exception = (HttpException) result.cause();
						sendGenericErrorPage(session.getId(), exception);
						response.setStatusCode(exception.getStatusCode());
						response.end(exception.getMessage());
					}
				}
			});
		} else {
			response.end();
		}
	}
	
	public void handlePreviousRequest(final HttpServerResponse response, String sessionId, Integer index) {
		final LocalSession session = sessions.get(sessionId);
		if (session == null) {
			response.setStatusCode(400).end("Unknown session id.");
			return;
		}
		Popup display = session.getDisplayFromHistory(index);
		if (display != null) {
			Popup popup = new Popup(display.asJson().copy()); // Clone to add buttons.
			JsonArray buttons = new JsonArray();
			if (session.getDisplayFromHistory(index + 1) != null) {
				JsonObject prevButton = new JsonObject();
				prevButton.putString("text", "Vorheriger Schritt");
				prevButton.putObject("action", new HttpPostAction(baseUrl + "/navigate/previous", new JsonObject().putNumber("index", index + 1)).asJson());
				buttons.addObject(prevButton);
			}
			if (index > 1) {
				JsonObject nextButton = new JsonObject();
				nextButton.putString("text", "Nächster Schritt");
				nextButton.putObject("action", new HttpPostAction(baseUrl + "/navigate/previous", new JsonObject().putNumber("index", index - 1)).asJson());
				buttons.addObject(nextButton);
			}
			if (buttons.size() > 0) {
				popup.asJson().putArray("buttons", buttons);
			}
			connectors.iidConnector().displayPopup(sessionId, null, MainVerticle.SERVICE_ID, popup, new AsyncResultHandler<Void>() {
				
				@Override
				public void handle(AsyncResult<Void> event) {
					if (event.failed()) {
						logger.warn("Failed to display previous step.", event.cause());
					}
					response.end();
				}
			});
		} else {
			// Request is ignored.
			response.end();
		}
	}
	
	public void handleCloseRequest(final HttpServerResponse response, String sessionId, final String token) {
		final LocalSession session = sessions.get(sessionId);
		if (session == null) {
			response.setStatusCode(400).end("Unknown session id.");
		}
		ProcessInstance processInstance = session.getActiveProcessInstance();
		connectors.pkiConnector().cancel(processInstance.getId(), session.getId(), new AsyncResultHandler<Void>() {
			
			@Override
			public void handle(AsyncResult<Void> terminateRequest) {
				if (terminateRequest.succeeded()) {
					response.end();
				} else {
					HttpException e = (HttpException) terminateRequest.cause();
					response.setStatusCode(e.getStatusCode()).end(e.getMessage());
				}
				connectors.iidConnector().endDisplay(session.getId(), MainVerticle.SERVICE_ID, null);
			}
		});
	}
	
	public void handleDetailsRequest(final HttpServerResponse response, String sessionId, final String token, final String activityProcessId) {
		final LocalSession session = sessions.get(sessionId);
		if (session == null) {
			response.setStatusCode(400).end("Unknown session id.");
			return;
		}
		final ProcessInstance processInstance = session.getActiveProcessInstance();
		ProcessElementInstance currentElement = session.getActiveElement();
		if (processInstance == null || currentElement == null) {
			response.setStatusCode(424);
			response.end("No support process running.");
			return;
		}
		if (currentElement.getType() != ProcessElementType.CALL_ACTIVITY ) {
			response.setStatusCode(424);
			response.end("Operation not available for current task.");
			return;
		}
		connectors.pkiConnector().getProcessDefinition(activityProcessId, new AsyncResultHandler<ProcessDefinition>() {
			@Override
			public void handle(AsyncResult<ProcessDefinition> processDefinitionRequest) {
				if (processDefinitionRequest.succeeded()) {
					final ProcessDefinition processDefinition = processDefinitionRequest.result();
					connectors.pkiConnector().confirm(processInstance.getId(), session.getId(), new AsyncResultHandler<ProcessInstance>() {
						
						@Override
						public void handle(AsyncResult<ProcessInstance> confirmRequest) {
							if (confirmRequest.succeeded()) {
								final ProcessInstance activityProcessInstance = confirmRequest.result();
								session.setActiveProcessInstance(activityProcessInstance);
								connectors.pkiConnector().getCurrentElement(activityProcessInstance.getId(), session.getId(), new AsyncResultHandler<ProcessElementInstance>() {
									@Override
									public void handle(AsyncResult<ProcessElementInstance> currentElementRequest) {
										if (currentElementRequest.succeeded()) {
											final ProcessElementInstance currentElement = currentElementRequest.result();
											response.end();
											session.setActiveElement(currentElement);
											String processId = processDefinition.getId();
											retrieveAndUpdateContentForTask(session, currentElement, processId, activityProcessInstance.getId(), processId, 0.0d);
										} else {
											response.setStatusCode(500).end(currentElementRequest.cause().getMessage());
										}
									}
								});
							} else {
								response.setStatusCode(500).end(confirmRequest.cause().getMessage());
							}
						}
					});
				} else {
					response.setStatusCode(500).end(processDefinitionRequest.cause().getMessage());
				}
			}
		});
	}
	
	public void setClientToken(String sessionId, String token) {
		LocalSession session = sessions.get(sessionId);
		if (session != null) {
			session.setToken(token);
		}
	}
}
