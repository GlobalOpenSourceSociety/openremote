/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.mqtt;

import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.InterceptHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.security.AuthContext;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.AssetEvent;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.TriggeredEventSubscription;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.ValueUtil;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.openremote.container.util.MapAccess.getInteger;
import static org.openremote.container.util.MapAccess.getString;
import static org.openremote.manager.event.ClientEventService.getSessionKey;
import static org.openremote.model.syslog.SyslogCategory.API;

public class MqttBrokerService implements ContainerService {

    public static final int PRIORITY = MED_PRIORITY;
    private static final Logger LOG = SyslogCategory.getLogger(API, MqttBrokerService.class);

    public static final String MQTT_CLIENT_QUEUE = "seda://MqttClientQueue?waitForTaskToComplete=IfReplyExpected&timeout=10000&purgeWhenStopping=true&discardIfNoConsumers=false&size=25000";
    public static final String MQTT_SERVER_LISTEN_HOST = "MQTT_SERVER_LISTEN_HOST";
    public static final String MQTT_SERVER_LISTEN_PORT = "MQTT_SERVER_LISTEN_PORT";

    public static final String ASSET_TOPIC = "asset";
    public static final String ATTRIBUTE_TOPIC = "attribute";
    public static final String ATTRIBUTE_VALUE_TOPIC = "attributevalue";
    public static final String SINGLE_LEVEL_WILDCARD = "+";
    public static final String MULTI_LEVEL_WILDCARD = "#";

    protected ManagerKeycloakIdentityProvider identityProvider;
    protected ClientEventService clientEventService;
    protected MessageBrokerService messageBrokerService;
    // Moquette doesn't provide any session id so cannot have multiple connections per realm-clientId combo
    protected Map<String, MqttConnection> sessionIdConnectionMap = new HashMap<>();
    protected final Set<MQTTCustomHandler> customHandlers = new CopyOnWriteArraySet<>();
    // This is not ideal (it'll keep filling with topics) but Moquette SPI is crappy - no association between Authoriser and Interceptor
    protected final Map<String, MQTTCustomHandler> topicCustomHandlerMap = new ConcurrentHashMap<>();

    protected boolean active;
    protected String host;
    protected int port;
    protected Server mqttBroker;

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void init(Container container) throws Exception {
        host = getString(container.getConfig(), MQTT_SERVER_LISTEN_HOST, BrokerConstants.HOST);
        port = getInteger(container.getConfig(), MQTT_SERVER_LISTEN_PORT, BrokerConstants.PORT);

        clientEventService = container.getService(ClientEventService.class);
        ManagerIdentityService identityService = container.getService(ManagerIdentityService.class);
        messageBrokerService = container.getService(MessageBrokerService.class);

        if (!identityService.isKeycloakEnabled()) {
            LOG.warning("MQTT connections are not supported when not using Keycloak identity provider");
            active = false;
        } else {
            active = true;
            identityProvider = (ManagerKeycloakIdentityProvider) identityService.getIdentityProvider();
        }

        mqttBroker = new Server();

        messageBrokerService.getContext().addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {

                from(MQTT_CLIENT_QUEUE)
                        .routeId("MqttClientEvents")
                        .choice()
                        .when(body().isInstanceOf(TriggeredEventSubscription.class))
                        .process(exchange -> {
                            String sessionKey = getSessionKey(exchange);
                            @SuppressWarnings("unchecked")
                            TriggeredEventSubscription<SharedEvent> triggeredEventSubscription = exchange.getIn().getBody(TriggeredEventSubscription.class);
                            MqttConnection connection = sessionIdConnectionMap.get(sessionKey);

                            if (connection == null) {
                                return;
                            }

                            onSubscriptionTriggered(connection, triggeredEventSubscription);
                        })
                        .end();
            }
        });
    }

    @Override
    public void start(Container container) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(BrokerConstants.HOST_PROPERTY_NAME, host);
        properties.setProperty(BrokerConstants.PORT_PROPERTY_NAME, String.valueOf(port));
        properties.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, String.valueOf(false));
        List<? extends InterceptHandler> interceptHandlers = Collections.singletonList(new ORInterceptHandler(this, identityProvider, messageBrokerService, sessionIdConnectionMap));

        AssetStorageService assetStorageService = container.getService(AssetStorageService.class);
        mqttBroker.startServer(new MemoryConfig(properties), interceptHandlers, null, new KeycloakAuthenticator(identityProvider, sessionIdConnectionMap), new ORAuthorizatorPolicy(identityProvider, this, assetStorageService, clientEventService));
        LOG.fine("Started MQTT broker");
    }

    @Override
    public void stop(Container container) throws Exception {
        mqttBroker.stopServer();
        LOG.fine("Stopped MQTT broker");
    }

    public void addCustomHandler(MQTTCustomHandler customHandler) {
        customHandlers.add(customHandler);
    }

    public void removeCustomHandler(MQTTCustomHandler customHandler) {
        customHandlers.remove(customHandler);
        topicCustomHandlerMap.values().removeIf(h -> h == customHandler);
    }

    public void sendToSession(String sessionId, String topic, Object data, MqttQoS qoS) {
        try {
            ByteBuf payload = Unpooled.copiedBuffer(ValueUtil.asJSON(data).orElseThrow(() -> new IllegalStateException("Failed to convert payload to JSON string: " + data)), Charset.defaultCharset());

            MqttPublishMessage publishMessage = MqttMessageBuilders.publish()
                .qos(qoS)
                .topicName(topic)
                .payload(payload)
                .build();

            mqttBroker.internalPublish(publishMessage, sessionId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't send AttributeEvent to MQTT client", e);
        }
    }

    public static boolean isAttributeTopic(List<String> tokens) {
        return tokens.get(2).equals(ATTRIBUTE_TOPIC) || tokens.get(2).equals(ATTRIBUTE_VALUE_TOPIC);
    }

    public static boolean isAssetTopic(List<String> tokens) {
        return tokens.get(2).equals(ASSET_TOPIC);
    }

    public static AssetFilter<?> buildAssetFilter(MqttConnection connection, List<String> topicTokens) {
        if (topicTokens == null || topicTokens.isEmpty()) {
            return null;
        }

        boolean isAttributeTopic = MqttBrokerService.isAttributeTopic(topicTokens);
        boolean isAssetTopic = MqttBrokerService.isAssetTopic(topicTokens);

        String realm = connection.getRealm();
        List<String> assetIds = new ArrayList<>();
        List<String> parentIds = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> attributeNames = new ArrayList<>();

        String assetId = Pattern.matches(Constants.ASSET_ID_REGEXP, topicTokens.get(3)) ?  topicTokens.get(3) : null;
        int multiLevelIndex = topicTokens.indexOf(MULTI_LEVEL_WILDCARD);
        int singleLevelIndex = topicTokens.indexOf(SINGLE_LEVEL_WILDCARD);

        if (!isAssetTopic && !isAttributeTopic) {
            return null;
        }

        if (topicTokens.size() == 4) {
            if (isAssetTopic) {
                if (multiLevelIndex == 3) {
                    //realm/clientId/.../#
                    // No asset filtering required
                } else if (singleLevelIndex == 3) {
                    //realm/clientId/.../+
                    parentIds.add(null);
                } else {
                    //realm/clientId/.../assetId
                    assetIds.add(assetId);
                }
            } else {
                if(assetId != null) {
                    //realm/clientId/attribute/assetId
                    assetIds.add(assetId);
                } else {
                    //realm/clientId/attribute/attributeName
                    attributeNames.add(topicTokens.get(3));
                }
            }
        } else if (topicTokens.size() == 5) {
            if (isAssetTopic) {
                if (multiLevelIndex == 4) {
                    //realm/clientId/asset/assetId/#
                    paths.add(assetId);
                } else if (singleLevelIndex == 4) {
                    //realm/clientId/asset/assetId/+
                    parentIds.add(assetId);
                } else {
                    return null;
                }
            } else {
                if (assetId != null) {
                    if (multiLevelIndex == 4) {
                        //realm/clientId/attribute/assetId/#
                        paths.add(assetId);
                    } else if (singleLevelIndex == 4) {
                        //realm/clientId/attribute/assetId/+
                        parentIds.add(assetId);
                    } else {
                        assetIds.add(assetId);

                        String attributeName = SINGLE_LEVEL_WILDCARD.equals(topicTokens.get(4)) || MULTI_LEVEL_WILDCARD.equals(topicTokens.get(4)) ? null : topicTokens.get(4);
                        if (attributeName != null) {
                            //realm/clientId/attribute/assetId/attributeName
                            attributeNames.add(topicTokens.get(4));
                        } else {
                            return null;
                        }
                    }
                } else {
                    String attributeName = SINGLE_LEVEL_WILDCARD.equals(topicTokens.get(4)) || MULTI_LEVEL_WILDCARD.equals(topicTokens.get(4)) ? null : topicTokens.get(4);
                    if (attributeName != null) {
                        attributeNames.add(attributeName);
                        if (multiLevelIndex == 4) {
                           return null; //no topic allowed after multilevel wildcard
                        } else if (singleLevelIndex == 4) {
                            //realm/clientId/attribute/+/attributeName
                            parentIds.add(null);
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                }
            }
        } else if (topicTokens.size() == 6) {
            if (isAssetTopic || assetId == null) {
                return null;
            }
            String attributeName = SINGLE_LEVEL_WILDCARD.equals(topicTokens.get(5)) || MULTI_LEVEL_WILDCARD.equals(topicTokens.get(5)) ? null : topicTokens.get(5);
            if (attributeName != null) {
                attributeNames.add(attributeName);
                if (multiLevelIndex == 4) {
                    return null; //no topic allowed after multilevel wildcard
                } else if (singleLevelIndex == 4) {
                    //realm/clientId/attribute/assetId/+/attributeName
                    parentIds.add(assetId);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } else {
            return null;
        }

        AssetFilter<?> assetFilter = new AssetFilter<>().setRealm(realm);
        if (!assetIds.isEmpty()) {
            assetFilter.setAssetIds(assetIds.toArray(new String[0]));
        }
        if (!parentIds.isEmpty()) {
            assetFilter.setParentIds(parentIds.toArray(new String[0]));
        }
        if(!paths.isEmpty()) {
            assetFilter.setPath(paths.toArray(new String[0]));
        }
        if (!attributeNames.isEmpty()) {
            assetFilter.setAttributeNames(attributeNames.toArray(new String[0]));
        }
        return assetFilter;
    }

    protected void onSubscriptionTriggered(MqttConnection connection, TriggeredEventSubscription<SharedEvent> triggeredEventSubscription) {
        triggeredEventSubscription.getEvents()
            .forEach(event -> {
                Consumer<SharedEvent> eventConsumer = connection.subscriptionHandlerMap.get(triggeredEventSubscription.getSubscriptionId());
                if (eventConsumer != null) {
                    eventConsumer.accept(event);
                }
            });
    }
    protected Consumer<SharedEvent> getEventConsumer(MqttConnection connection, String topic, boolean isValueSubscription, MqttQoS mqttQoS) {
        return ev -> {
            List<String> topicTokens = Arrays.asList(topic.split("/"));
            int wildCardIndex = Math.max(topicTokens.indexOf(MULTI_LEVEL_WILDCARD), topicTokens.indexOf(SINGLE_LEVEL_WILDCARD));

            if (ev instanceof AssetEvent) {
                AssetEvent assetEvent = (AssetEvent) ev;
                if (wildCardIndex > 0) {
                    topicTokens.set(wildCardIndex, assetEvent.getAssetId());
                }
                sendToSession(connection.getSessionId(), String.join("/", topicTokens), ev, mqttQoS);
            }

            if (ev instanceof AttributeEvent) {
                AttributeEvent attributeEvent = (AttributeEvent) ev;
                if (wildCardIndex > 0) {
                    if (wildCardIndex == 1) { // attribute/<wildcard>
                        topicTokens.set(wildCardIndex, attributeEvent.getAssetId());
                    } else if (wildCardIndex == 2) {
                        if (topicTokens.size() == 3) { // attribute/assetId/<wildcard>
                            topicTokens.set(wildCardIndex, attributeEvent.getAttributeName());
                        } else { // attribute/parentId/<wildcard>/attributeName
                            topicTokens.set(wildCardIndex, attributeEvent.getAssetId());
                        }
                    } else if (wildCardIndex == 3) { //attribute/parentId/assetId/<wildcard>
                        topicTokens.set(wildCardIndex, attributeEvent.getAttributeName());
                    }
                }
                if(isValueSubscription) {
                    sendToSession(connection.getSessionId(), String.join("/", topicTokens), attributeEvent.getValue().orElse(null), mqttQoS);
                } else {
                    sendToSession(connection.getSessionId(), String.join("/", topicTokens), ev, mqttQoS);
                }
            }
        };
    }

    protected Boolean customHandlerAuthorises(AuthContext authContext, MqttConnection connection, Topic topic, boolean isWrite) {
        // See if a custom handler wants to handle this topic
        for (MQTTCustomHandler handler : customHandlers) {
            Boolean result = handler.shouldIntercept(authContext, connection, topic, isWrite);
            if (result != null) {
                topicCustomHandlerMap.put(topic.toString(), handler);
                LOG.info("Custom handler intercepted request: handler=" + handler.getName() + ", topic=" + topic + ", connection=" + connection);
                return result;
            }
        }

        return null;
    }

    /**
     * Looks for a custom handler that authorised this topic and if found it return the {@link InterceptHandler}
     * otherwise returns null.
     */
    protected MQTTCustomHandler getCustomInterceptHandler(String topic) {
        return topicCustomHandlerMap.get(topic);
    }
}
