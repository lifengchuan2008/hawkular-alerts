/**
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.alerts.rest
import org.hawkular.alerts.api.model.Severity
import org.hawkular.alerts.api.model.condition.AvailabilityCondition
import org.hawkular.alerts.api.model.condition.ThresholdCondition
import org.hawkular.alerts.api.model.data.Availability
import org.hawkular.alerts.api.model.data.MixedData
import org.hawkular.alerts.api.model.data.NumericData
import org.hawkular.alerts.api.model.trigger.Trigger
import org.hawkular.bus.restclient.RestClient
import org.junit.FixMethodOrder
import org.junit.Test

import static org.hawkular.alerts.api.model.condition.AvailabilityCondition.Operator
import static org.hawkular.alerts.api.model.trigger.Trigger.Mode
import static org.junit.Assert.assertEquals
import static org.junit.runners.MethodSorters.NAME_ASCENDING
/**
 * Alerts REST tests.
 *
 * @author Jay Shaughnessy
 */
@FixMethodOrder(NAME_ASCENDING)
class LifecycleITest extends AbstractITestBase {

    static host = System.getProperty('hawkular.host') ?: '127.0.0.1'
    static port = Integer.valueOf(System.getProperty('hawkular.port') ?: "8080")
    static t01Start = String.valueOf(System.currentTimeMillis())
    static t02Start;

    @Test
    void t01_disableTest() {
        String start = t01Start;

        // CREATE the trigger
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        Trigger testTrigger = new Trigger("test-autodisable-trigger", "test-autodisable-trigger");

        // remove if it exists
        resp = client.delete(path: "triggers/test-autodisable-trigger")
        assert(200 == resp.status || 404 == resp.status)

        testTrigger.setAutoDisable(true);
        testTrigger.setAutoResolve(false);
        testTrigger.setSeverity(Severity.LOW);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // ADD Firing condition
        AvailabilityCondition firingCond = new AvailabilityCondition("test-autodisable-trigger",
                Mode.FIRING, "test-autodisable-avail", Operator.NOT_UP);

        resp = client.post(path: "triggers/test-autodisable-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ENABLE Trigger
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-autodisable-trigger/", body: testTrigger)
        assertEquals(200, resp.status)

        // FETCH trigger and make sure it's as expected
        resp = client.get(path: "triggers/test-autodisable-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-autodisable-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(true, resp.data.autoDisable);
        assertEquals(false, resp.data.autoEnable);
        assertEquals("LOW", resp.data.severity);

        // FETCH recent alerts for trigger, should not be any
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autodisable-trigger"] )
        assertEquals(200, resp.status)

        // Send in avail data to fire the trigger
        // Note, the groovyx rest c;lient seems incapable of allowing a JSON payload and a TEXT response (which is what
        // we get back from the activemq rest client used by the bus), so use the bus' java rest client to do this.
        RestClient busClient = new RestClient(host, port);
        String json = "{\"data\":[{\"id\":\"test-autodisable-avail\",\"timestamp\":" + System.currentTimeMillis() +
                      ",\"value\"=\"DOWN\",\"type\"=\"availability\"}]}";
        busClient.postTopicMessage("HawkularAlertData", json, null);
        //assertEquals(200, resp.status)

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent alerts for trigger, there should be 1
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autodisable-trigger"] )
            if ( resp.status == 200 && resp.data != null && resp.data.size > 0) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)

        String alertId = resp.data[0].alertId;

        // FETCH trigger and make sure it's disabled
        resp = client.get(path: "triggers/test-autodisable-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-autodisable-trigger", resp.data.name)
        assertEquals(false, resp.data.enabled)

        // RESOLVE manually the alert
        resp = client.put(path: "resolve", query: [alertIds:alertId,resolvedBy:"testUser",resolvedNotes:"testNotes"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autodisable-trigger"] )
        assertEquals(200, resp.status)
        assertEquals("RESOLVED", resp.data[0].status)
        assertEquals("testUser", resp.data[0].resolvedBy)
        assertEquals("testNotes", resp.data[0].resolvedNotes)
        assert null == resp.data[0].resolvedEvalSets

        // FETCH trigger and make sure it's still disabled, because autoEnable was set to false
        resp = client.get(path: "triggers/test-autodisable-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-autodisable-trigger", resp.data.name)
        assertEquals(false, resp.data.enabled)
    }

    @Test
    void t02_autoResolveTest() {
        String start = t02Start = String.valueOf(System.currentTimeMillis());

        // CREATE the trigger
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        Trigger testTrigger = new Trigger("test-autoresolve-trigger", "test-autoresolve-trigger");

        // remove if it exists
        resp = client.delete(path: "triggers/test-autoresolve-trigger")
        assert(200 == resp.status || 404 == resp.status)

        testTrigger.setAutoDisable(false);
        testTrigger.setAutoResolve(true);
        testTrigger.setAutoResolveAlerts(true);
        testTrigger.setSeverity(Severity.HIGH);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // ADD Firing condition
        AvailabilityCondition firingCond = new AvailabilityCondition("test-autoresolve-trigger",
                Mode.FIRING, "test-autoresolve-avail", Operator.NOT_UP);

        resp = client.post(path: "triggers/test-autoresolve-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ADD AutoResolve condition
        AvailabilityCondition autoResolveCond = new AvailabilityCondition("test-autoresolve-trigger",
                Mode.AUTORESOLVE, "test-autoresolve-avail", Operator.UP);

        resp = client.post(path: "triggers/test-autoresolve-trigger/conditions", body: autoResolveCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ENABLE Trigger
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-autoresolve-trigger", body: testTrigger)
        assertEquals(200, resp.status)

        // FETCH trigger and make sure it's as expected
        resp = client.get(path: "triggers/test-autoresolve-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-autoresolve-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(false, resp.data.autoDisable);
        assertEquals(true, resp.data.autoResolve);
        assertEquals(true, resp.data.autoResolveAlerts);
        assertEquals("HIGH", resp.data.severity);

        // FETCH recent alerts for trigger, should not be any
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-trigger"] )
        assertEquals(200, resp.status)

        // Send in DOWN avail data to fire the trigger
        // Instead of going through the bus, in this test we'll use the alerts rest API directly to send data
        Availability avail = new Availability("test-autoresolve-avail", System.currentTimeMillis(), "DOWN");
        MixedData mixedData = new MixedData();
        mixedData.getAvailability().add(avail);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent alerts for trigger, there should be 1
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-trigger"] )
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)

        // ACK the alert
        resp = client.put(path: "ack", query: [alertIds:resp.data[0].alertId,ackBy:"testUser",ackNotes:"testNotes"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "",
            query: [startTime:start,triggerIds:"test-autoresolve-trigger",statuses:"ACKNOWLEDGED"] )
        assertEquals(200, resp.status)
        assertEquals("ACKNOWLEDGED", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)
        assertEquals("testUser", resp.data[0].ackBy)
        assertEquals("testNotes", resp.data[0].ackNotes)

        // FETCH trigger and make sure it's still enabled (note - we can't check the mode as that is runtime
        // info and not supplied in the returned json)
        resp = client.get(path: "triggers/test-autoresolve-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-autoresolve-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)

        // Send in UP avail data to autoresolve the trigger
        // Instead of going through the bus, in this test we'll use the alerts rest API directly to send data
        avail = new Availability("test-autoresolve-avail", System.currentTimeMillis(), "UP");
        mixedData.clear();
        mixedData.getAvailability().add(avail);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent alerts for trigger, there should be 1
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-trigger",statuses:"RESOLVED"] )
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("RESOLVED", resp.data[0].status)
        assertEquals("AUTO", resp.data[0].resolvedBy)
    }

    @Test
    void t03_manualResolutionTest() {
        String start = String.valueOf(System.currentTimeMillis());

        // CREATE the trigger
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        Trigger testTrigger = new Trigger("test-manual-trigger", "test-manual-trigger");

        // remove if it exists
        resp = client.delete(path: "triggers/test-manual-trigger")
        assert(200 == resp.status || 404 == resp.status)

        testTrigger.setAutoDisable(false);
        testTrigger.setAutoResolve(false);
        testTrigger.setAutoResolveAlerts(false);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // ADD Firing condition
        AvailabilityCondition firingCond = new AvailabilityCondition("test-manual-trigger",
                Mode.FIRING, "test-manual-avail", Operator.NOT_UP);

        resp = client.post(path: "triggers/test-manual-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ENABLE Trigger
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-manual-trigger", body: testTrigger)
        assertEquals(200, resp.status)

        // FETCH trigger and make sure it's as expected
        resp = client.get(path: "triggers/test-manual-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-manual-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(false, resp.data.autoDisable);
        assertEquals(false, resp.data.autoResolve);
        assertEquals(false, resp.data.autoResolveAlerts);

        // FETCH recent alerts for trigger, should not be any
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-trigger"] )
        assertEquals(200, resp.status)

        // Send in DOWN avail data to fire the trigger
        // Instead of going through the bus, in this test we'll use the alerts rest API directly to send data
        for (int i=0; i<5; i++) {
            Availability avail = new Availability("test-manual-avail", System.currentTimeMillis(), "DOWN");
            MixedData mixedData = new MixedData();
            mixedData.getAvailability().add(avail);
            resp = client.post(path: "data", body: mixedData);
            assertEquals(200, resp.status)
        }

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent alerts for trigger, there should be 5
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-trigger"] )
            if ( resp.status == 200 && resp.data.size() == 5 ) {
                break;
            }
        }
        assertEquals(200, resp.status)
        assertEquals(5, resp.data.size())
        assertEquals("OPEN", resp.data[0].status)

        // RESOLVE manually the alert
        resp = client.put(path: "resolve", query: [alertIds:resp.data[0].alertId,resolvedBy:"testUser",
                resolvedNotes:"testNotes"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"OPEN"] )
        assertEquals(200, resp.status)
        assertEquals(4, resp.data.size())

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"RESOLVED"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
    }

    // Given name ordering this test runs after tests 1..3, it uses the alerts generated in those tests
    // to test alert queries and updates.
    @Test
    void t04_fetchTest() {
        // queries will look for alerts generated in this test tun
        String start = t01Start;

        // FETCH alerts for bogus trigger, should not be any
        def resp = client.get(path: "", query: [startTime:start,triggerIds:"XXX"] )
        assertEquals(200, resp.status)

        // FETCH alerts for bogus alert id, should not be any
        resp = client.get(path: "", query: [startTime:start,alertIds:"XXX,YYY"] )
        assertEquals(200, resp.status)

        // FETCH alerts for bogus tag, should not be any
        resp = client.get(path: "", query: [startTime:start,tags:"XXX"] )
        assertEquals(200, resp.status)

        // FETCH alerts for bogus category|tag, should not be any
        resp = client.get(path: "", query: [startTime:start,tags:"XXX|YYY"] )
        assertEquals(200, resp.status)

        // FETCH alerts for just triggers generated in test t01, by time, should be 1
        resp = client.get(path: "", query: [startTime:t01Start,endTime:t02Start] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals("test-autodisable-trigger", resp.data[0].triggerId)

        // FETCH the alert above again, this time by alert id
        def alertId = resp.data[0].alertId
        resp = client.get(path: "", query: [startTime:start,alertIds:"XXX,"+alertId] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals(alertId, resp.data[0].alertId)

        // FETCH the alert above again, this time by tag
        resp = client.get(path: "", query: [startTime:start,tags:"dataId|test-autodisable-avail"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals(alertId, resp.data[0].alertId)

        // FETCH the alert above again, this time by union of (good) triggerId and (bad) tag
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autodisable-trigger",tags:"XXX"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals(alertId, resp.data[0].alertId)

        // FETCH alerts for test-manual-trigger, there should be 5 from the earlier test
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-trigger"] )
        assertEquals(200, resp.status)
        assertEquals(5, resp.data.size())

        // 4 OPEN and 1 RESOLVED
        resp = client.get(path: "",
            query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"OPEN,RESOLVED"] )
        assertEquals(200, resp.status)
        assertEquals(5, resp.data.size())

        resp = client.get(path: "",
            query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"OPEN,RESOLVED,ACKNOWLEDGED"] )
        assertEquals(200, resp.status)
        assertEquals(5, resp.data.size())

        resp = client.get(path: "",
            query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"OPEN,ACKNOWLEDGED"] )
        assertEquals(200, resp.status)
        assertEquals(4, resp.data.size())

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"ACKNOWLEDGED"] )
        assertEquals(200, resp.status)

        // FETCH by severity (1 HIGH and 1 LOW, five MEDIUM)
        resp = client.get(path: "", query: [startTime:start,severities:"CRITICAL"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "", query: [startTime:start,severities:"LOW,HIGH,MEDIUM"] )
        assertEquals(200, resp.status)
        assertEquals(7, resp.data.size())

        resp = client.get(path: "", query: [startTime:start,severities:"LOW"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals("LOW", resp.data[0].severity)
        assertEquals("test-autodisable-trigger", resp.data[0].triggerId)

        resp = client.get(path: "", query: [startTime:start,severities:"HIGH"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals("HIGH", resp.data[0].severity)
        assertEquals("test-autoresolve-trigger", resp.data[0].triggerId)

        // test thinning as well as verifying the RESOLVED status fetch (using the autoresolve alert)
        resp = client.get(path: "",
            query: [startTime:start,triggerIds:"test-autoresolve-trigger",statuses:"RESOLVED",thin:false] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals("RESOLVED", resp.data[0].status)
        assertEquals("AUTO", resp.data[0].resolvedBy)
        assert null != resp.data[0].evalSets
        assert null != resp.data[0].resolvedEvalSets
        assert !resp.data[0].evalSets.isEmpty()
        assert !resp.data[0].resolvedEvalSets.isEmpty()

        resp = client.get(path: "",
            query: [startTime:start,triggerIds:"test-autoresolve-trigger",statuses:"RESOLVED",thin:true] )
        assertEquals(200, resp.status)
        assertEquals("RESOLVED", resp.data[0].status)
        assertEquals("AUTO", resp.data[0].resolvedBy)
        assert null == resp.data[0].evalSets
        assert null == resp.data[0].resolvedEvalSets
    }

    @Test
    void t05_paging() {
        // queries will look for alerts generated in this test tun
        String start = t01Start;

        // 4 OPEN and 1 RESOLVED
        def resp = client.get(path: "",
                query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"OPEN,RESOLVED", page: "0", per_page: "3"] )
        assertEquals(200, resp.status)
        assertEquals(3, resp.data.size())

        println(resp.headers)

        resp = client.get(path: "",
                query: [startTime:start,triggerIds:"test-manual-trigger",statuses:"OPEN,RESOLVED", page: "1", per_page: "3"] )
        assertEquals(200, resp.status)
        assertEquals(2, resp.data.size())

        println(resp.headers)
    }

    @Test
    void t06_manualAckAndResolutionTest() {
        String start = String.valueOf(System.currentTimeMillis());

        // CREATE the trigger
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        Trigger testTrigger = new Trigger("test-manual2-trigger", "test-manual2-trigger");

        // remove if it exists
        resp = client.delete(path: "triggers/test-manual2-trigger")
        assert(200 == resp.status || 404 == resp.status)

        testTrigger.setAutoDisable(false);
        testTrigger.setAutoResolve(false);
        testTrigger.setAutoResolveAlerts(false);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // ADD Firing condition
        AvailabilityCondition firingCond = new AvailabilityCondition("test-manual2-trigger",
                Mode.FIRING, "test-manual2-avail", Operator.NOT_UP);

        resp = client.post(path: "triggers/test-manual2-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ENABLE Trigger
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-manual2-trigger", body: testTrigger)
        assertEquals(200, resp.status)

        // FETCH trigger and make sure it's as expected
        resp = client.get(path: "triggers/test-manual2-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-manual2-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(false, resp.data.autoDisable);
        assertEquals(false, resp.data.autoResolve);
        assertEquals(false, resp.data.autoResolveAlerts);

        // FETCH recent alerts for trigger, should not be any
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual2-trigger"] )
        assertEquals(200, resp.status)

        // Send in DOWN avail data to fire the trigger
        // Instead of going through the bus, in this test we'll use the alerts rest API directly to send data
        for (int i=0; i<5; i++) {
            Availability avail = new Availability("test-manual2-avail", System.currentTimeMillis(), "DOWN");
            MixedData mixedData = new MixedData();
            mixedData.getAvailability().add(avail);
            resp = client.post(path: "data", body: mixedData);
            assertEquals(200, resp.status)
        }

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(1000);

            // FETCH recent alerts for trigger, there should be 5
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual2-trigger"] )
            if ( resp.status == 200 && resp.data != null && resp.data.size() == 5 ) {
                break;
            }
        }
        assertEquals(200, resp.status)
        assertEquals(5, resp.data.size())
        assertEquals("OPEN", resp.data[0].status)

        def alertId1 = resp.data[0].alertId;
        def alertId2 = resp.data[1].alertId;
        // RESOLVE manually 1 alert
        resp = client.get(path: "resolve/" + alertId1,
                query: [resolvedBy:"testUser", resolvedNotes:"testNotes"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "ack/" + alertId2,
                query: [resolvedBy:"testUser", resolvedNotes:"testNotes"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual2-trigger",statuses:"OPEN"] )
        assertEquals(200, resp.status)
        assertEquals(3, resp.data.size())

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual2-trigger",statuses:"ACKNOWLEDGED"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual2-trigger",statuses:"RESOLVED"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
    }

    @Test
    void t07_autoResolveWithThresholdTest() {
        String start = String.valueOf(System.currentTimeMillis());

        /*
            Step 0: Check REST API is up and running
         */
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        /*
            Step 1: Remove previous existing definition for this test
         */
        resp = client.delete(path: "triggers/test-autoresolve-threshold-trigger")
        assert(200 == resp.status || 404 == resp.status)

        /*
            Step 2: Create a new trigger
         */
        Trigger testTrigger = new Trigger("test-autoresolve-threshold-trigger", "http://www.myresource.com");
        testTrigger.setAutoDisable(false);
        testTrigger.setAutoResolve(true);
        testTrigger.setAutoResolveAlerts(false);
        testTrigger.setSeverity(Severity.HIGH);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        /*
            Step 3: Create a threshold FIRING condition
                    Fires when ResponseTime > 100 ms
                    "Normal" scenario, the condition represents a "bad" situation we want to monitor and alert
         */
        ThresholdCondition firingCond = new ThresholdCondition("test-autoresolve-threshold-trigger",
                Mode.FIRING, "test-autoresolve-threshold", ThresholdCondition.Operator.GT, 100);

        resp = client.post(path: "triggers/test-autoresolve-threshold-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        /*
            Step 4: Create a threshold AUTORESOLVE condition
                    Fires when ResponseTime <= 100 ms
                    "Resolution" scenario, the condition represent a "good" situation to enable again "Normal" scenario
                    Typically it should be the "opposite" than
         */
        ThresholdCondition autoResolveCond = new ThresholdCondition("test-autoresolve-threshold-trigger",
                Mode.AUTORESOLVE, "test-autoresolve-threshold", ThresholdCondition.Operator.LTE, 100);

        resp = client.post(path: "triggers/test-autoresolve-threshold-trigger/conditions", body: autoResolveCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        /*
            Step 5: Enable the trigger to accept data
         */
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-autoresolve-threshold-trigger", body: testTrigger)
        assertEquals(200, resp.status)

        /*
            Step 6: Check trigger is created correctly
         */
        resp = client.get(path: "triggers/test-autoresolve-threshold-trigger");
        assertEquals(200, resp.status)
        assertEquals("http://www.myresource.com", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(false, resp.data.autoDisable);
        assertEquals(true, resp.data.autoResolve);
        assertEquals(false, resp.data.autoResolveAlerts);
        assertEquals("HIGH", resp.data.severity);

        /*
            Step 7: Check there is not alerts for this trigger at this point
         */
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-threshold-trigger"] )
        assertEquals(200, resp.status)

        /*
            Step 8: Sending "bad" data to fire the trigger
                    Using direct API instead bus messages
         */
        NumericData responseTime = new NumericData("test-autoresolve-threshold", System.currentTimeMillis(), 101);
        MixedData mixedData = new MixedData();
        mixedData.getNumericData().add(responseTime);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        /*
             Step 9: Wait until the engine detects the data, matches the conditions and sends an alert
         */
        for ( int i=0; i < 10; ++i ) {
            Thread.sleep(500);
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-threshold-trigger"] )
            /*
                We should have only 1 alert
             */
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)

        /*
            Step 10: Sending "bad" data to fire the trigger
         */
        responseTime = new NumericData("test-autoresolve-threshold", System.currentTimeMillis(), 102);
        mixedData = new MixedData();
        mixedData.getNumericData().add(responseTime);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        /*
             Step 11: Wait until the engine detects the data
                      It should retrieve only 1 data not 2 as previous data shouldn't generate a new alert
         */
        for ( int i=0; i < 10; ++i ) {
            Thread.sleep(500);
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-threshold-trigger"] )
            /*
                We should have only 1 alert
             */
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)

        /*
            Step 12: Sending "good" data to change trigger from FIRING to AUTORESOLVE
         */
        responseTime = new NumericData("test-autoresolve-threshold", System.currentTimeMillis(), 102);
        mixedData = new MixedData();
        mixedData.getNumericData().add(responseTime);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        /*
             Step 13: Wait until the engine detects the data
                      It should retrieve only 1 data not 2 as previous data shouldn't generate a new alert
         */
        for ( int i=0; i < 10; ++i ) {
            Thread.sleep(500);
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-threshold-trigger"] )
            /*
                We should have only 1 alert
             */
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)

        /*
            Step 14: Sending "bad" data to fire the trigger
         */
        responseTime = new NumericData("test-autoresolve-threshold", System.currentTimeMillis(), 103);
        mixedData = new MixedData();
        mixedData.getNumericData().add(responseTime);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        /*
             Step 15: Wait until the engine detects the data
                      It should retrieve 2 data
         */
        for ( int i=0; i < 10; ++i ) {
            Thread.sleep(500);
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoresolve-threshold-trigger"] )
            /*
                We should have only 2 alert
             */
            if ( resp.status == 200 && resp.data.size() == 2 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)
    }

    @Test
    void t08_autoEnableTest() {
        String start = String.valueOf(System.currentTimeMillis());

        // CREATE the trigger
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        Trigger testTrigger = new Trigger("test-autoenable-trigger", "test-autoenable-trigger");

        // remove if it exists
        resp = client.delete(path: "triggers/test-autoenable-trigger")
        assert(200 == resp.status || 404 == resp.status)

        testTrigger.setAutoDisable(true);
        testTrigger.setAutoEnable(true);
        testTrigger.setAutoResolve(false);
        testTrigger.setAutoResolveAlerts(false);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // ADD Firing condition
        AvailabilityCondition firingCond = new AvailabilityCondition("test-autoenable-trigger",
                Mode.FIRING, "test-autoenable-avail", Operator.NOT_UP);

        resp = client.post(path: "triggers/test-autoenable-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ENABLE Trigger
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-autoenable-trigger", body: testTrigger)
        assertEquals(200, resp.status)

        // FETCH trigger and make sure it's as expected
        resp = client.get(path: "triggers/test-autoenable-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-autoenable-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(true, resp.data.autoDisable);
        assertEquals(true, resp.data.autoEnable);
        assertEquals(false, resp.data.autoResolve);
        assertEquals(false, resp.data.autoResolveAlerts);

        // FETCH recent alerts for trigger, should not be any
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoenable-trigger"] )
        assertEquals(200, resp.status)

        // Send in DOWN avail data to fire the trigger
        // Instead of going through the bus, in this test we'll use the alerts rest API directly to send data
        for (int i=0; i<2; i++) {
            Availability avail = new Availability("test-autoenable-avail", System.currentTimeMillis(), "DOWN");
            MixedData mixedData = new MixedData();
            mixedData.getAvailability().add(avail);
            resp = client.post(path: "data", body: mixedData);
            assertEquals(200, resp.status)
        }

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent alerts for trigger, there should be 1 because the trigger should have disabled after firing
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoenable-trigger"] )
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
        }
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())
        assertEquals("OPEN", resp.data[0].status)

        // FETCH trigger and make sure it's disabled
        def resp2 = client.get(path: "triggers/test-autoenable-trigger");
        assertEquals(200, resp2.status)
        assertEquals("test-autoenable-trigger", resp2.data.name)
        assertEquals(false, resp2.data.enabled)

        // RESOLVE manually the alert
        resp = client.put(path: "resolve", query: [alertIds:resp.data[0].alertId,resolvedBy:"testUser",
                resolvedNotes:"testNotes"] )
        assertEquals(200, resp.status)

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoenable-trigger",statuses:"OPEN"] )
        assertEquals(200, resp.status)
        assertEquals(0, resp.data.size())

        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-autoenable-trigger",statuses:"RESOLVED"] )
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // at the time of this writing we don't have a service to purge/delete alerts, so for this to work we
        // have to make sure any old alerts (from prior runs) are also resolved. Because all alerts for the trigger
        // must be resolved for autoEnable to kick in...
        resp = client.get(path: "", query: [triggerIds:"test-autoenable-trigger",statuses:"OPEN"] )
        assertEquals(200, resp.status)
        for(int i=0; i < resp.data.size(); ++i) {
            resp2 = client.put(path: "resolve", query: [alertIds:resp.data[i].alertId,resolvedBy:"testUser",
                resolvedNotes:"testNotes"] )
            assertEquals(200, resp2.status)
        }

        // FETCH trigger and make sure it's now enabled
        resp2 = client.get(path: "triggers/test-autoenable-trigger");
        assertEquals(200, resp2.status)
        assertEquals("test-autoenable-trigger", resp2.data.name)
        assertEquals(true, resp2.data.enabled)
    }

    @Test
    void t09_manualAutoResolveTest() {
        String start = String.valueOf(System.currentTimeMillis());

        // CREATE the trigger
        def resp = client.get(path: "")
        assert resp.status == 200 : resp.status

        Trigger testTrigger = new Trigger("test-manual-autoresolve-trigger", "test-manual-autoresolve-trigger");

        // remove if it exists
        resp = client.delete(path: "triggers/test-manual-autoresolve-trigger")
        assert(200 == resp.status || 404 == resp.status)

        testTrigger.setAutoDisable(false);
        testTrigger.setAutoResolve(true);
        testTrigger.setAutoResolveAlerts(true);
        testTrigger.setSeverity(Severity.HIGH);

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // ADD Firing condition
        AvailabilityCondition firingCond = new AvailabilityCondition("test-manual-autoresolve-trigger",
                Mode.FIRING, "test-manual-autoresolve-avail", Operator.NOT_UP);

        resp = client.post(path: "triggers/test-manual-autoresolve-trigger/conditions", body: firingCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ADD AutoResolve condition
        AvailabilityCondition autoResolveCond = new AvailabilityCondition("test-manual-autoresolve-trigger",
                Mode.AUTORESOLVE, "test-autoresolve-avail", Operator.UP);

        resp = client.post(path: "triggers/test-manual-autoresolve-trigger/conditions", body: autoResolveCond)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // ENABLE Trigger
        testTrigger.setEnabled(true);

        resp = client.put(path: "triggers/test-manual-autoresolve-trigger", body: testTrigger)
        assertEquals(200, resp.status)

        // FETCH trigger and make sure it's as expected
        resp = client.get(path: "triggers/test-manual-autoresolve-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-manual-autoresolve-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)
        assertEquals(false, resp.data.autoDisable);
        assertEquals(false, resp.data.autoEnable);
        assertEquals(true, resp.data.autoResolve);
        assertEquals(true, resp.data.autoResolveAlerts);
        assertEquals("HIGH", resp.data.severity);

        // FETCH recent alerts for trigger, should not be any
        resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-autoresolve-trigger"] )
        assertEquals(200, resp.status)

        // Send in DOWN avail data to fire the trigger
        // Instead of going through the bus, in this test we'll use the alerts rest API directly to send data
        Availability avail = new Availability("test-manual-autoresolve-avail", System.currentTimeMillis(), "DOWN");
        MixedData mixedData = new MixedData();
        mixedData.getAvailability().add(avail);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent alerts for trigger, there should be 1
            resp = client.get(path: "", query: [startTime:start,triggerIds:"test-manual-autoresolve-trigger"] )
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
        assertEquals("HIGH", resp.data[0].severity)

        // FETCH trigger and make sure it's still enabled (note - we can't check the mode as that is runtime
        // info and not supplied in the returned json)
        resp = client.get(path: "triggers/test-manual-autoresolve-trigger");
        assertEquals(200, resp.status)
        assertEquals("test-manual-autoresolve-trigger", resp.data.name)
        assertEquals(true, resp.data.enabled)

        // Manually RESOLVE the alert prior to an autoResolve
        // Att the time of this writing we don't have a service to purge/delete alerts, so for this to work we
        // have to make sure any old alerts (from prior runs) are also resolved. Because all alerts for the trigger
        // must be resolved for autoEnable to kick in...
        resp = client.get(path: "", query: [triggerIds:"test-manual-autoresolve-trigger",statuses:"OPEN"] )
        assertEquals(200, resp.status)
        for(int i=0; i < resp.data.size(); ++i) {
            def resp2 = client.put(path: "resolve", query: [alertIds:resp.data[i].alertId,resolvedBy:"testUser",
                resolvedNotes:"testNotes"] )
            assertEquals(200, resp2.status)
        }

        // Send in another DOWN data and we should get another alert assuming the trigger was reset to Firing mode
        avail = new Availability("test-manual-autoresolve-avail", System.currentTimeMillis(), "DOWN");
        mixedData = new MixedData();
        mixedData.getAvailability().add(avail);
        resp = client.post(path: "data", body: mixedData);
        assertEquals(200, resp.status)

        // The alert processing happens async, so give it a little time before failing...
        for ( int i=0; i < 10; ++i ) {
            // println "SLEEP!" ;
            Thread.sleep(500);

            // FETCH recent OPEN alerts for trigger, there should be 1
            resp = client.get(path: "",
                query: [startTime:start,triggerIds:"test-manual-autoresolve-trigger",statuses:"OPEN"] )
            if ( resp.status == 200 && resp.data.size() == 1 ) {
                break;
            }
            assert resp.status == 200 : resp.status
        }
        assertEquals(200, resp.status)
        assertEquals("OPEN", resp.data[0].status)
    }

    @Test
    void t100_cleanup() {
        // clean up triggers
        def resp = client.delete(path: "triggers/test-autodisable-trigger")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.delete(path: "triggers/test-autoresolve-trigger")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.delete(path: "triggers/test-manual-trigger")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.delete(path: "triggers/test-manual2-trigger")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.delete(path: "triggers/test-autoresolve-threshold-trigger")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.delete(path: "triggers/test-autoenable-trigger")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.delete(path: "triggers/test-manual-autoresolve-trigger")
        assert(200 == resp.status || 404 == resp.status)

    }
}
