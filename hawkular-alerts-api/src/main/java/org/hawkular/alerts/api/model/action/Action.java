/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.api.model.action;

import java.io.Serializable;

import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.model.event.Thin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * An action is the abstract concept of a consequence of an event.
 *
 * An Action object represents a particular action linked with a specific event.
 * Action objects are generated by the Alerts engine and processed by plugins.
 * An Action object stores the eventId property and optionally may contain the full Event object.
 * An Action may store the result of the processing by a plugin.
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
public class Action extends ActionDefinition implements Serializable {

    @JsonInclude
    private String eventId;

    @JsonInclude
    private long ctime;

    @Thin
    @JsonInclude(Include.NON_NULL)
    private Event event;

    @JsonInclude(Include.NON_NULL)
    private String result;

    public Action() {
    }

    public Action(String tenantId, String actionPlugin, String actionId, Event event) {
        super(tenantId, actionPlugin, actionId);
        this.event = event;
        if (event != null) {
            this.eventId = event.getId();
        }
        this.ctime = System.currentTimeMillis();
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public long getCtime() {
        return ctime;
    }

    public void setCtime(long ctime) {
        this.ctime = ctime;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        Action action = (Action) o;

        if (ctime != action.ctime) return false;
        if (eventId != null ? !eventId.equals(action.eventId) : action.eventId != null) return false;
        if (event != null ? !event.equals(action.event) : action.event != null) return false;
        return result != null ? result.equals(action.result) : action.result == null;

    }

    @Override
    public int hashCode() {
        int result1 = super.hashCode();
        result1 = 31 * result1 + (eventId != null ? eventId.hashCode() : 0);
        result1 = 31 * result1 + (int) (ctime ^ (ctime >>> 32));
        result1 = 31 * result1 + (event != null ? event.hashCode() : 0);
        result1 = 31 * result1 + (result != null ? result.hashCode() : 0);
        return result1;
    }

    @Override
    public String toString() {
        return "Action" + '[' +
                "eventId='" + eventId + '\'' +
                ", ctime=" + ctime +
                ", event=" + event +
                ", result='" + result + '\'' +
                ']';
    }
}
