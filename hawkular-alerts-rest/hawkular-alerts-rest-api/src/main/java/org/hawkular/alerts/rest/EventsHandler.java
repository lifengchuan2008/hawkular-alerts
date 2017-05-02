/*
 * Copyright 2015-2017 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.alerts.rest;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import static org.hawkular.alerts.rest.CommonUtil.checkTags;
import static org.hawkular.alerts.rest.CommonUtil.isEmpty;
import static org.hawkular.alerts.rest.CommonUtil.parseTagQuery;
import static org.hawkular.alerts.rest.CommonUtil.parseTags;
import static org.hawkular.alerts.rest.HawkularAlertsApp.TENANT_HEADER_NAME;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.model.paging.Page;
import org.hawkular.alerts.api.model.paging.Pager;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.api.services.EventsCriteria;
import org.hawkular.alerts.rest.ResponseUtil.ApiDeleted;
import org.hawkular.alerts.rest.ResponseUtil.ApiError;
import org.jboss.logging.Logger;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * REST endpoint for events
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@Path("/events")
@Api(value = "/events", description = "Event Handling")
public class EventsHandler {
    private final Logger log = Logger.getLogger(EventsHandler.class);

    private static final Map<String, Set<String>> queryParamValidationMap = new HashMap<>();

    static {
        ResponseUtil.populateQueryParamsMap(EventsHandler.class, queryParamValidationMap);
    }

    @HeaderParam(TENANT_HEADER_NAME)
    String tenantId;

    @EJB
    AlertsService alertsService;

    @EJB
    StreamWatcher streamWatcher;

    public EventsHandler() {
        log.debug("Creating instance.");
    }

    @POST
    @Path("/")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Create a new Event.",
            notes = "Persist the new event and send it to the engine for processing/condition evaluation. + \n" +
                    "Returns created Event.",
            response = Event.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event Created."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response createEvent(
            @ApiParam(value = "Event to be created. Category and Text fields required,",
                    name = "event", required = true)
            final Event event) {
        try {
            if (null != event) {
                if (isEmpty(event.getId())) {
                    return ResponseUtil.badRequest("Event with id null.");
                }
                if (isEmpty(event.getCategory())) {
                    return ResponseUtil.badRequest("Event with category null.");
                }
                event.setTenantId(tenantId);
                if (null != alertsService.getEvent(tenantId, event.getId(), true)) {
                    return ResponseUtil.badRequest("Event with ID [" + event.getId() + "] exists.");
                }
                if (!checkTags(event.getTags())) {
                    return ResponseUtil.badRequest("Tags " + event.getTags() + " must be non empty.");
                }
                /*
                    New events are sent directly to the engine for inference process.
                    Input events and new ones generated by the alerts engine are persisted at the end of the process.
                 */
                alertsService.addEvents(Collections.singletonList(event));
                log.debugf("Event: %s", event);
                return ResponseUtil.ok(event);
            }
            return ResponseUtil.badRequest("Event is null");

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @POST
    @Path("/data")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiOperation(
            value = "Send events to the engine for processing/condition evaluation. ",
            notes = "Only events generated by the engine are persisted. + \n" +
                    "Input events are treated as external data and those are not persisted into the system.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event Created."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response sendEvents(
            @ApiParam(required = true, name = "events", value = "Events to be processed by alerting.")
            final Collection<Event> events) {
        try {
            if (isEmpty(events)) {
                return ResponseUtil.badRequest("Events are empty");
            }

            events.stream().forEach(e -> e.setTenantId(tenantId));
            alertsService.sendEvents(events);
            log.debugf("Sent Events: %s", events);
            return ResponseUtil.ok();

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @PUT
    @Path("/tags")
    @Consumes(APPLICATION_JSON)
    @ApiOperation(value = "Add tags to existing Events.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Events tagged successfully."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response addTags(
            @ApiParam(required = true, value = "List of eventIds to tag.",
                allowableValues = "Comma separated list of events IDs.")
            @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = true, value = "List of tags to add.",
                    allowableValues = "Comma separated list of tags. + \n" +
                            "Each tag of format 'name\\|value'.")
            @QueryParam("tags")
            final String tags) {
        try {
            if (!isEmpty(eventIds) && !isEmpty(tags)) {
                List<String> eventIdList = Arrays.asList(eventIds.split(","));
                Map<String, String> tagsMap = parseTags(tags);
                alertsService.addEventTags(tenantId, eventIdList, tagsMap);
                log.debugf("Tagged alertIds:%s, %s", eventIdList, tagsMap);
                return ResponseUtil.ok();
            }
            return ResponseUtil.badRequest("EventIds and Tags required for adding tags");

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @DELETE
    @Path("/tags")
    @Consumes(APPLICATION_JSON)
    @ApiOperation(value = "Remove tags from existing Events.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Events untagged successfully."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response deleteTags(
            @ApiParam(required = true, value = "List of events to untag.",
                    allowableValues = "Comma separated list of event IDs.")
            @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = true, value = "List of tag names to remove.",
                    allowableValues = "Comma separated list of tags names.")
            @QueryParam("tagNames")
            final String tagNames) {
        try {
            if (!isEmpty(eventIds) && !isEmpty(tagNames)) {
                Collection<String> ids = Arrays.asList(eventIds.split(","));
                Collection<String> tags = Arrays.asList(tagNames.split(","));
                alertsService.removeEventTags(tenantId, ids, tags);
                log.debugf("Untagged eventIds:%s, %s", ids, tags);
                return ResponseUtil.ok();
            }
            return ResponseUtil.badRequest("EventIds and Tags required for removing tags");

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @GET
    @Path("/")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Get events with optional filtering.",
            notes = "If not criteria defined, it fetches all events stored in the system. + \n" +
                    "Tags Query language (BNF): + \n" +
                    "[source] \n" +
                    "---- \n" +
                    "<tag_query> ::= ( <expression> | \"(\" <object> \")\" " +
                    "| <object> <logical_operator> <object> ) \n" +
                    "<expression> ::= ( <tag_name> | <not> <tag_name> " +
                    "| <tag_name> <boolean_operator> <tag_value> | " +
                    "<tag_name> <array_operator> <array> ) \n" +
                    "<not> ::= [ \"NOT\" | \"not\" ] \n" +
                    "<logical_operator> ::= [ \"AND\" | \"OR\" | \"and\" | \"or\" ] \n" +
                    "<boolean_operator> ::= [ \"=\" | \"!=\" ] \n" +
                    "<array_operator> ::= [ \"IN\" | \"NOT IN\" | \"in\" | \"not in\" ] \n" +
                    "<array> ::= ( \"[\" \"]\" | \"[\" ( \",\" <tag_value> )* ) \n" +
                    "<tag_name> ::= <identifier> \n" +
                    "<tag_value> ::= ( \"'\" <regexp> \"'\" | <simple_value> ) \n" +
                    "; \n" +
                    "; <identifier> and <simple_value> follow pattern [a-zA-Z_0-9][\\-a-zA-Z_0-9]* \n" +
                    "; <regexp> follows any valid Java Regular Expression format \n" +
                    "---- \n",
            response = Event.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successfully fetched list of events."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    @QueryParamValidation(name = "findEvents")
    public Response findEvents(
            @ApiParam(required = false, value = "Filter out events created before this time.",
                allowableValues = "Timestamp in millisecond since epoch.")
            @QueryParam("startTime")
            final Long startTime,
            @ApiParam(required = false, value = "Filter out events created after this time.",
                allowableValues = "Timestamp in millisecond since epoch.")
            @QueryParam("endTime")
            final Long endTime,
            @ApiParam(required = false, value = "Filter out events for unspecified eventIds.",
                allowableValues = "Comma separated list of event IDs.")
            @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = false, value = "Filter out events for unspecified triggers.",
                allowableValues = "Comma separated list of trigger IDs.")
            @QueryParam("triggerIds")
            final String triggerIds,
            @ApiParam(required = false, value = "Filter out events for unspecified categories. ",
                allowableValues = "Comma separated list of category values.")
            @QueryParam("categories")
            final String categories,
            @ApiParam(required = false, value = "[DEPRECATED] Filter out events for unspecified tags.",
                    allowableValues = "Comma separated list of tags, each tag of format 'name\\|value'. + \n" +
                            "Specify '*' for value to match all values.")
            @QueryParam("tags")
            final String tags,
            @ApiParam(required = false, value = "Filter out events for unspecified tags.",
                    allowableValues = "A tag query expression.")
            @QueryParam("tagQuery")
            final String tagQuery,
            @ApiParam(required = false, value = "Return only thin events, do not include: evalSets.")
            @QueryParam("thin")
            final Boolean thin,
            @Context
            final UriInfo uri) {
        try {
            ResponseUtil.checkForUnknownQueryParams(uri, queryParamValidationMap.get("findEvents"));
            Pager pager = RequestUtil.extractPaging(uri);

            /*
                We maintain old tags criteria as deprecated (it can be removed in a next major version).
                If present, the tags criteria has precedence over tagQuery parameter.
             */
            String unifiedTagQuery;
            if (!isEmpty(tags)) {
                unifiedTagQuery = parseTagQuery(parseTags(tags));
            } else {
                unifiedTagQuery = tagQuery;
            }
            EventsCriteria criteria = new EventsCriteria(startTime, endTime, eventIds, triggerIds, categories,
                    unifiedTagQuery, thin);
            Page<Event> eventPage = alertsService.getEvents(tenantId, criteria, pager);
            log.debugf("Events: %s", eventPage);
            if (isEmpty(eventPage)) {
                return ResponseUtil.ok(eventPage);
            }
            return ResponseUtil.paginatedOk(eventPage, uri);

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @GET
    @Path("/watch")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Watch events with optional filtering.",
            notes = "Return a stream of events ordered by ctime. + \n" +
                    " + \n" +
                    "If not criteria defined, it fetches all events stored in the system. + \n" +
                    " + \n" +
                    "Time criterias are used only for the initial query. + \n" +
                    "After initial query, time criterias are discarded, watching events by ctime. + \n" +
                    "Non time criterias are active. + \n" +
                    "If not criteria defined, it fetches all events stored in the system. + \n" +
                    "Tags Query language (BNF): + \n" +
                    "[source] \n" +
                    "---- \n" +
                    "<tag_query> ::= ( <expression> | \"(\" <object> \")\" " +
                    "| <object> <logical_operator> <object> ) \n" +
                    "<expression> ::= ( <tag_name> | <not> <tag_name> " +
                    "| <tag_name> <boolean_operator> <tag_value> | " +
                    "<tag_name> <array_operator> <array> ) \n" +
                    "<not> ::= [ \"NOT\" | \"not\" ] \n" +
                    "<logical_operator> ::= [ \"AND\" | \"OR\" | \"and\" | \"or\" ] \n" +
                    "<boolean_operator> ::= [ \"=\" | \"!=\" ] \n" +
                    "<array_operator> ::= [ \"IN\" | \"NOT IN\" | \"in\" | \"not in\" ] \n" +
                    "<array> ::= ( \"[\" \"]\" | \"[\" ( \",\" <tag_value> )* ) \n" +
                    "<tag_name> ::= <identifier> \n" +
                    "<tag_value> ::= ( \"'\" <regexp> \"'\" | <simple_value> ) \n" +
                    "; \n" +
                    "; <identifier> and <simple_value> follow pattern [a-zA-Z_0-9][\\-a-zA-Z_0-9]* \n" +
                    "; <regexp> follows any valid Java Regular Expression format \n" +
                    "---- \n",
            response = Event.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Stream of events.", response = Event.class),
            @ApiResponse(code = 200, message = "Errors will close the stream. Description is sent before stream is closed.", response = ResponseUtil.ApiError.class)
    })
    @QueryParamValidation(name = "watchEvents")
    public Response watchEvents(
            @ApiParam(required = false, value = "Filter out events created before this time.",
                    allowableValues = "Timestamp in millisecond since epoch.")
            @QueryParam("startTime")
            final Long startTime,
            @ApiParam(required = false, value = "Filter out events created after this time.",
                    allowableValues = "Timestamp in millisecond since epoch.")
            @QueryParam("endTime")
            final Long endTime,
            @ApiParam(required = false, value = "Filter out events for unspecified eventIds.",
                    allowableValues = "Comma separated list of event IDs.")
            @QueryParam("eventIds") final String eventIds,
            @ApiParam(required = false, value = "Filter out events for unspecified triggers.",
                    allowableValues = "Comma separated list of trigger IDs.")
            @QueryParam("triggerIds")
            final String triggerIds,
            @ApiParam(required = false, value = "Filter out events for unspecified categories. ",
                    allowableValues = "Comma separated list of category values.")
            @QueryParam("categories")
            final String categories,
            @ApiParam(required = false, value = "[DEPRECATED] Filter out events for unspecified tags.",
                    allowableValues = "Comma separated list of tags, each tag of format 'name\\|value'. + \n" +
                            "Specify '*' for value to match all values.")
            @QueryParam("tags")
            final String tags,
            @ApiParam(required = false, value = "Filter out events for unspecified tags.",
                    allowableValues = "A tag query expression.")
            @QueryParam("tagQuery")
            final String tagQuery,
            @ApiParam(required = false, value = "Define interval when watcher notifications will be sent.",
                    allowableValues = "Interval in seconds")
            @QueryParam("watchInterval")
            final Long watchInterval,
            @ApiParam(required = false, value = "Return only thin events, do not include: evalSets.")
            @QueryParam("thin")
            final Boolean thin,
            @Context
            final UriInfo uri) {
        try {
            ResponseUtil.checkForUnknownQueryParams(uri, queryParamValidationMap.get("watchEvents"));

            String unifiedTagQuery;
            if (!isEmpty(tags)) {
                unifiedTagQuery = parseTagQuery(parseTags(tags));
            } else {
                unifiedTagQuery = tagQuery;
            }
            EventsCriteria criteria = new EventsCriteria(startTime, endTime, eventIds, triggerIds, categories,
                    unifiedTagQuery, thin);
            return Response.ok(streamWatcher.watchEvents(Collections.singleton(tenantId), criteria, watchInterval))
                    .build();

        } catch (Exception e) {
            log.debug(e.getMessage(), e);
            if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
                return ResponseUtil.badRequest("Bad arguments: " + e.getMessage());
            }
            return ResponseUtil.internalError(e);
        }
    }

    @DELETE
    @Path("/{eventId}")
    @ApiOperation(value = "Delete an existing Event.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event deleted."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 404, message = "Event not found.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response deleteEvent(
            @ApiParam(required = true, value = "Event id to be deleted.")
            @PathParam("eventId")
            final String eventId) {
        try {
            EventsCriteria criteria = new EventsCriteria();
            criteria.setEventId(eventId);
            int numDeleted = alertsService.deleteEvents(tenantId, criteria);
            if (1 == numDeleted) {
                log.debugf("EventId: %s", eventId);
                return ResponseUtil.ok();
            }
            return ResponseUtil.notFound("Event " + eventId + " doesn't exist for delete");

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @PUT
    @Path("/delete")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Delete events with optional filtering.",
            notes = "Return number of events deleted. + \n" +
                    "WARNING: If not criteria defined, it deletes all events stored in the system. + \n" +
                    "Tags Query language (BNF): + \n" +
                    "[source] \n" +
                    "---- \n" +
                    "<tag_query> ::= ( <expression> | \"(\" <object> \")\" " +
                    "| <object> <logical_operator> <object> ) \n" +
                    "<expression> ::= ( <tag_name> | <not> <tag_name> " +
                    "| <tag_name> <boolean_operator> <tag_value> | " +
                    "<tag_name> <array_operator> <array> ) \n" +
                    "<not> ::= [ \"NOT\" | \"not\" ] \n" +
                    "<logical_operator> ::= [ \"AND\" | \"OR\" | \"and\" | \"or\" ] \n" +
                    "<boolean_operator> ::= [ \"=\" | \"!=\" ] \n" +
                    "<array_operator> ::= [ \"IN\" | \"NOT IN\" | \"in\" | \"not in\" ] \n" +
                    "<array> ::= ( \"[\" \"]\" | \"[\" ( \",\" <tag_value> )* ) +\n" +
                    "<tag_name> ::= <identifier> \n" +
                    "<tag_value> ::= ( \"'\" <regexp> \"'\" | <simple_value> ) \n" +
                    "; \n " +
                    "; <identifier> and <simple_value> follow pattern [a-zA-Z_0-9][\\-a-zA-Z_0-9]* \n" +
                    "; <regexp> follows any valid Java Regular Expression format \n" +
                    "---- \n",
            response = ApiDeleted.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    @QueryParamValidation(name = "deleteEvents")
    public Response deleteEvents(
            @ApiParam(required = false, value = "Filter out events created before this time.",
                allowableValues = "Timestamp in millisecond since epoch.")
            @QueryParam("startTime")
            final Long startTime,
            @ApiParam(required = false, value = "Filter out events created after this time.",
                allowableValues = "Timestamp in millisecond since epoch.")
            @QueryParam("endTime")
            final Long endTime,
            @ApiParam(required = false, value = "Filter out events for unspecified eventIds. ",
                allowableValues = "Comma separated list of event IDs.")
            @QueryParam("eventIds")
            final String eventIds,
            @ApiParam(required = false, value = "Filter out events for unspecified triggers. ",
                allowableValues = "Comma separated list of trigger IDs.")
            @QueryParam("triggerIds")
            final String triggerIds,
            @ApiParam(required = false, value = "Filter out events for unspecified categories. ",
                allowableValues = "Comma separated list of category values.")
            @QueryParam("categories")
            final String categories,
            @ApiParam(required = false, value = "[DEPRECATED] Filter out events for unspecified tags.",
                    allowableValues = "Comma separated list of tags, each tag of format 'name\\|value'. + \n" +
                            "Specify '*' for value to match all values.")
            @QueryParam("tags")
            final String tags,
            @ApiParam(required = false, value = "Filter out events for unspecified tags.",
                    allowableValues = "A tag query expression.")
            @QueryParam("tagQuery")
            final String tagQuery,
            @Context
            final UriInfo uri
            ) {
        try {
            ResponseUtil.checkForUnknownQueryParams(uri, queryParamValidationMap.get("deleteEvents"));

            /*
                We maintain old tags criteria as deprecated (it can be removed in a next major version).
                If present, the tags criteria has precedence over tagQuery parameter.
             */
            String unifiedTagQuery;
            if (!isEmpty(tags)) {
                unifiedTagQuery = parseTagQuery(parseTags(tags));
            } else {
                unifiedTagQuery = tagQuery;
            }
            EventsCriteria criteria = new EventsCriteria(startTime, endTime, eventIds, triggerIds, categories,
                    unifiedTagQuery, null);
            int numDeleted = alertsService.deleteEvents(tenantId, criteria);
            log.debugf("Events deleted: %d", numDeleted);
            return ResponseUtil.ok(new ApiDeleted(numDeleted));

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }

    @GET
    @Path("/event/{eventId}")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Get an existing Event.",
            response = Event.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Success, Event found."),
            @ApiResponse(code = 400, message = "Bad Request/Invalid Parameters.", response = ApiError.class),
            @ApiResponse(code = 404, message = "Event not found.", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal server error.", response = ApiError.class)
    })
    public Response getEvent(
            @ApiParam(value = "Id of Event to be retrieved.", required = true)
            @PathParam("eventId")
            final String eventId,
            @ApiParam(required = false, value = "Return only a thin event, do not include: evalSets, dampening.")
            @QueryParam("thin")
            final Boolean thin) {
        try {
            Event found = alertsService.getEvent(tenantId, eventId, ((null == thin) ? false : thin.booleanValue()));
            if (found != null) {
                log.debugf("Event: %s", found);
                return ResponseUtil.ok(found);
            }
            return ResponseUtil.notFound("eventId: " + eventId + " not found");

        } catch (Exception e) {
            return ResponseUtil.onException(e, log);
        }
    }
}
