package de.appsist.service.ps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.appsist.service.iid.server.model.Popup;
import de.appsist.service.pki.model.ProcessElementInstance;
import de.appsist.service.pki.model.ProcessInstance;

public class LocalSession {
	private final String sessionId;
	private final String userId;
	private final Map<String, ProcessInstance> processInstances;
	
	private ProcessInstance activeProcessInstance;
	private ProcessElementInstance activeElement;
	private String token;
	private Double progress;
	private Popup contactPopup;
	
	private final Map<String, String> contentMappingCache;
	
	private final List<Popup> displayHistory;
	
	public LocalSession(String sessionId, String userId) {
		this.sessionId = sessionId;
		this.userId = userId;
		this.processInstances = new HashMap<>();
		this.contentMappingCache = new HashMap<>();
		this.progress = 0.0d;
		this.displayHistory = new ArrayList<>();
	}
	
	public String getId() {
		return sessionId;
	}
	
	public String getUserId() {
		return userId;
	}
	
	public void setActiveProcessInstance(ProcessInstance processInstance) {
		processInstances.put(processInstance.getId(), processInstance);
		activeProcessInstance = processInstance;
	}
	
	public void closeProcessInstance() {
		processInstances.remove(activeProcessInstance);
		activeProcessInstance = null;
	}
	
	public ProcessInstance getActiveProcessInstance() {
		return activeProcessInstance;
	}
	
	public void setActiveElement(ProcessElementInstance activeElement) {
		this.activeElement = activeElement;
	}
	
	public ProcessElementInstance getActiveElement() {
		return activeElement;
	}
	
	public void setToken(String token) {
		this.token = token;
	}
	
	public String getToken() {
		return token;
	}
	
	public void setProgress(Double progress) {
		this.progress = progress;
	}
	
	public Double getProgress() {
		return progress;
	}
	
	public void setCurrentDisplay(Popup popup) {
		this.displayHistory.add(0, popup);
	}
	
	public Popup getLastDisplay() {
		return displayHistory.size() > 1 ? displayHistory.get(1) : null;
	}
	
	/**
	 * Returns the n-th last display. 
	 * @param index Index of the history do display. 0 requests the current display, 1 the last, 2 the second last and so on.
	 * @return Popup to display or <code>null</code> if the history is shorter than the given index.
	 */
	public Popup getDisplayFromHistory(int index) {
		if (displayHistory.size() > index) {
			return displayHistory.get(index);
		} else {
			return null;
		}
	}
	
	public boolean hasLastDisplay() {
		return displayHistory.size() >= 1;
	}
	
	public void setContentForStep(String stepId, String contentId) {
		contentMappingCache.put(stepId, contentId);
	}
	
	public String getCachedContent(String stepId) {
		return contentMappingCache.get(stepId);
	}
	
	public void setContactPopup(Popup popup) {
		this.contactPopup = popup;
	}
	
	public Popup getContactsPopup() {
		return contactPopup;
	}
}
