/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.server.jgroups;

import org.infinispan.server.jgroups.logging.JGroupsLogger;
import org.infinispan.server.jgroups.security.RealmAuthorizationCallbackHandler;
import org.infinispan.server.jgroups.security.SaslClientCallbackHandler;
import org.infinispan.server.jgroups.spi.ChannelFactory;
import org.infinispan.server.jgroups.spi.ProtocolConfiguration;
import org.infinispan.server.jgroups.spi.ProtocolStackConfiguration;
import org.infinispan.server.jgroups.spi.RelayConfiguration;
import org.infinispan.server.jgroups.spi.RemoteSiteConfiguration;
import org.infinispan.server.jgroups.spi.SaslConfiguration;
import org.infinispan.server.jgroups.spi.TransportConfiguration;
import org.jboss.as.domain.management.SecurityRealm;
import org.jboss.as.network.SocketBinding;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.RequestCorrelator;
import org.jgroups.blocks.RequestCorrelator.Header;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.conf.PropertyConverters;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.fork.UnknownForkHandler;
import org.jgroups.protocols.FORK;
import org.jgroups.protocols.SASL;
import org.jgroups.protocols.TP;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.relay.RELAY2;
import org.jgroups.protocols.relay.RouteStatusListener;
import org.jgroups.protocols.relay.config.RelayConfig;
import org.jgroups.stack.Configurator;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Util;
import org.wildfly.security.manager.WildFlySecurityManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Supplier;

import static org.infinispan.server.jgroups.logging.JGroupsLogger.ROOT_LOGGER;

/**
 * Factory for creating fork-able channels.
 * @author Paul Ferraro
 */
public class JChannelFactory implements ChannelFactory, ProtocolStackConfigurator {

    static final ByteBuffer UNKNOWN_FORK_RESPONSE = ByteBuffer.allocate(0);

    private final ProtocolStackConfiguration configuration;

    public JChannelFactory(ProtocolStackConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public ProtocolStackConfiguration getProtocolStackConfiguration() {
        return this.configuration;
    }

    @Override
    public JChannel createChannel(final String id) throws Exception {
        JGroupsLogger.ROOT_LOGGER.debugf("Creating channel %s from stack %s", id, this.configuration.getName());

        PrivilegedExceptionAction<JChannel> action = new PrivilegedExceptionAction<JChannel>() {
            @Override
            public JChannel run() throws Exception {
                return new JChannel(JChannelFactory.this);
            }
        };
        final JChannel channel = WildFlySecurityManager.doChecked(action);
        ProtocolStack stack = channel.getProtocolStack();

        // We need to synchronize on shared transport,
        // so we don't attempt to init a shared transport multiple times
        TP transport = stack.getTransport();
            this.init(transport);

        // Relay protocol is added to stack programmatically, not via ProtocolStackConfigurator
        RelayConfiguration relayConfig = this.configuration.getRelay();
        if (relayConfig != null) {
            String localSite = relayConfig.getSiteName();
            List<RemoteSiteConfiguration> remoteSites = this.configuration.getRelay().getRemoteSites();
            List<String> sites = new ArrayList<>(remoteSites.size() + 1);
            sites.add(localSite);
            // Collect bridges, eliminating duplicates
            Map<String, RelayConfig.BridgeConfig> bridges = new HashMap<>();
            for (final RemoteSiteConfiguration remoteSite: remoteSites) {
                String siteName = remoteSite.getName();
                sites.add(siteName);
                String clusterName = remoteSite.getClusterName();
                RelayConfig.BridgeConfig bridge = new RelayConfig.BridgeConfig(clusterName) {
                    @Override
                    public JChannel createChannel() throws Exception {
                        JChannel channel = remoteSite.getChannel();
                        // Don't use FORK in bridge stack
                        channel.getProtocolStack().removeProtocol(FORK.class);
                        return channel;
                    }
                };
                bridges.put(clusterName, bridge);
            }
            RELAY2 relay = new RELAY2().site(localSite);
            relay.setRouteStatusListener(new DefaultRouteStatusListener());
            for (String site: sites) {
                RelayConfig.SiteConfig siteConfig = new RelayConfig.SiteConfig(site);
                relay.addSite(site, siteConfig);
                if (site.equals(localSite)) {
                    for (RelayConfig.BridgeConfig bridge: bridges.values()) {
                        siteConfig.addBridge(bridge);
                    }
                }
            }
            Configurator.resolveAndAssignFields(relay, relayConfig.getProperties());
            Configurator.resolveAndInvokePropertyMethods(relay, relayConfig.getProperties());
            stack.addProtocol(relay);
            relay.init();
        }

        UnknownForkHandler unknownForkHandler = new UnknownForkHandler() {
            private final short id = ClassConfigurator.getProtocolId(RequestCorrelator.class);

            @Override
            public Object handleUnknownForkStack(Message message, String forkStackId) {
                return this.handle(message);
            }

            @Override
            public Object handleUnknownForkChannel(Message message, String forkChannelId) {
                return this.handle(message);
            }

            private Object handle(Message message) {
                Header header = message.getHeader(this.id);
                // If this is a request expecting a response, don't leave the requester hanging - send an identifiable response on which it can filter
                if ((header != null) && (header.type == Header.REQ) && header.rspExpected()) {
                    Message response = message.makeReply().setFlag(message.getFlags()).clearFlag(Message.Flag.RSVP);

                    response.putHeader(FORK.ID, message.getHeader(FORK.ID));
                    response.putHeader(this.id, new Header(Header.RSP, header.req_id, this.id));
                    response.setBuffer(UNKNOWN_FORK_RESPONSE.array());

                    channel.down(response);
                }
                return null;
            }
        };

        // Add implicit FORK to the top of the stack
        FORK fork = new FORK();
        fork.setUnknownForkHandler(unknownForkHandler);
        stack.addProtocol(fork);
        fork.init();

        // Handle the Sasl protocol
        final SaslConfiguration saslConfig = this.configuration.getSasl();
        if (saslConfig != null) {
            final String clusterRole = saslConfig.getClusterRole();
            final SecurityRealm securityRealm = saslConfig.getSecurityRealm();
            final String mech = saslConfig.getMech();
            final SASL sasl = new SASL();
            sasl.setMech(mech);
            Map<String, String> props = saslConfig.getProperties();
            if (props.containsKey("client_password")) {
                String credential = props.get("client_password");
                String name = props.get("client_name");
                if (name == null) {
                    sasl.setClientCallbackHandler(new SaslClientCallbackHandler(securityRealm.getName(),
                            this.configuration.getNodeName(), credential));
                } else if (name.contains("@")) {
                    sasl.setClientCallbackHandler(new SaslClientCallbackHandler(name, credential));
                } else {
                    sasl.setClientCallbackHandler(
                            new SaslClientCallbackHandler(securityRealm.getName(), name, credential));
                }
            } else {
                props.put("client_password", ""); // HACKY
            }
            Map<String, String> saslProps = props.containsKey("sasl_props")
                    ? Util.parseCommaDelimitedProps(props.get("sasl_props")) : new HashMap<String, String>();
            sasl.setServerCallbackHandler(new RealmAuthorizationCallbackHandler(securityRealm, mech,
                    clusterRole != null ? clusterRole : id, saslProps));
            props.put("sasl_props", new PropertyConverters.StringProperties().toString(saslProps));
            Configurator.resolveAndAssignFields(sasl, props);
            Configurator.resolveAndInvokePropertyMethods(sasl, props);
            channel.getProtocolStack().insertProtocol(sasl, ProtocolStack.Position.BELOW, GMS.class);
            sasl.init();
        }

        channel.setName(this.configuration.getNodeName());

        TransportConfiguration.Topology topology = this.configuration.getTransport().getTopology();
        if (topology != null) {
            channel.addAddressGenerator(new TopologyAddressGenerator(topology));
        }

        return channel;
    }

    @Override
    public boolean isUnknownForkResponse(ByteBuffer buffer) {
        return UNKNOWN_FORK_RESPONSE.equals(buffer);
    }

    private void init(TP transport) {
        TransportConfiguration transportConfig = this.configuration.getTransport();
        SocketBinding binding = transportConfig.getSocketBinding();
        // TODO Uncomment when ISPN-7145 is fixed and SocketBindingManager.UnnamedBindingRegistryImpl no longer throws IllegalStateException for unbound sockets
//        if (binding != null) {
//            SocketFactory factory = transport.getSocketFactory();
//            if (!(factory instanceof ManagedSocketFactory)) {
//                transport.setSocketFactory(new ManagedSocketFactory(factory, binding.getSocketBindings()));
//            }
//        }
    }

    /**
     * {@inheritDoc}
     * @see org.jgroups.conf.ProtocolStackConfigurator#getProtocolStackString()
     */
    @Override
    public String getProtocolStackString() {
        return null;
    }

    /**
     * {@inheritDoc}
     * @see org.jgroups.conf.ProtocolStackConfigurator#getProtocolStack()
     */
    @Override
    public List<org.jgroups.conf.ProtocolConfiguration> getProtocolStack() {
        List<org.jgroups.conf.ProtocolConfiguration> stack = new ArrayList<>(this.configuration.getProtocols().size() + 1);
        TransportConfiguration transport = this.configuration.getTransport();
        org.jgroups.conf.ProtocolConfiguration protocol = createProtocol(this.configuration, transport);
        Map<String, String> properties = protocol.getProperties();

        Introspector introspector = new Introspector(protocol);

        SocketBinding binding = transport.getSocketBinding();
        if (binding != null) {
            configureBindAddress(introspector, protocol, binding);
            configureServerSocket(introspector, protocol, "bind_port", binding);
            configureMulticastSocket(introspector, protocol, "mcast_addr", "mcast_port", binding);
        }

        SocketBinding diagnosticsSocketBinding = transport.getDiagnosticsSocketBinding();
        boolean diagnostics = (diagnosticsSocketBinding != null);
        properties.put("enable_diagnostics", String.valueOf(diagnostics));
        if (diagnostics) {
            configureMulticastSocket(introspector, protocol, "diagnostics_addr", "diagnostics_port", diagnosticsSocketBinding);
        }

        stack.add(protocol);

        final Class<? extends TP> transportClass = introspector.getProtocolClass().asSubclass(TP.class);
        PrivilegedExceptionAction<TP> action = new PrivilegedExceptionAction<TP>() {
            @Override
            public TP run() throws InstantiationException, IllegalAccessException {
                return transportClass.newInstance();
            }
        };

        try {
            stack.addAll(createProtocols(this.configuration, WildFlySecurityManager.doChecked(action).isMulticastCapable()));
        } catch (PrivilegedActionException e) {
            throw new IllegalStateException(e.getCause());
        }

        return stack;
    }

    static List<org.jgroups.conf.ProtocolConfiguration> createProtocols(ProtocolStackConfiguration stack, boolean multicastCapable) {

        List<ProtocolConfiguration> protocols = stack.getProtocols();
        List<org.jgroups.conf.ProtocolConfiguration> result = new ArrayList<>(protocols.size());
        TransportConfiguration transport = stack.getTransport();

        for (ProtocolConfiguration protocol: protocols) {
            org.jgroups.conf.ProtocolConfiguration config = createProtocol(stack, protocol);
            Introspector introspector = new Introspector(config);
            SocketBinding binding = protocol.getSocketBinding();
            if (binding != null) {
                configureBindAddress(introspector, config, binding);
                configureServerSocket(introspector, config, "bind_port", binding);
                configureServerSocket(introspector, config, "start_port", binding);
                configureMulticastSocket(introspector, config, "mcast_addr", "mcast_port", binding);
            } else if (transport.getSocketBinding() != null) {
                // If no socket-binding was specified, use bind address of transport
                configureBindAddress(introspector, config, transport.getSocketBinding());
            }
            if (!multicastCapable) {
                setProperty(introspector, config, "use_mcast_xmit", String.valueOf(false));
                setProperty(introspector, config, "use_mcast_xmit_req", String.valueOf(false));
            }
            result.add(config);
        }

        return result;
    }

    private static org.jgroups.conf.ProtocolConfiguration createProtocol(ProtocolStackConfiguration stack, ProtocolConfiguration protocol) {
        String protocolName = protocol.getName();
        ModuleIdentifier module = protocol.getModule();
        final Map<String, String> properties = new HashMap<>(stack.getDefaultProperties(protocolName));
        properties.putAll(protocol.getProperties());
        try {
            return new org.jgroups.conf.ProtocolConfiguration(protocol.getProtocolClassName(), properties, stack.getModuleLoader().loadModule(module).getClassLoader()) {
                @Override
                public Map<String, String> getOriginalProperties() {
                    return properties;
                }
            };
        } catch (ModuleLoadException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void configureBindAddress(Introspector introspector, org.jgroups.conf.ProtocolConfiguration config, SocketBinding binding) {
        setSocketBindingProperty(introspector, config, "bind_addr", binding.getSocketAddress().getAddress().getHostAddress());
    }

    private static void configureServerSocket(Introspector introspector, org.jgroups.conf.ProtocolConfiguration config, String property, SocketBinding binding) {
        setSocketBindingProperty(introspector, config, property, String.valueOf(binding.getSocketAddress().getPort()));
    }

    private static void configureMulticastSocket(Introspector introspector, org.jgroups.conf.ProtocolConfiguration config, String addressProperty, String portProperty, SocketBinding binding) {
        try {
            InetSocketAddress mcastSocketAddress = binding.getMulticastSocketAddress();
            setSocketBindingProperty(introspector, config, addressProperty, mcastSocketAddress.getAddress().getHostAddress());
            setSocketBindingProperty(introspector, config, portProperty, String.valueOf(mcastSocketAddress.getPort()));
        } catch (IllegalStateException e) {
            ROOT_LOGGER.couldNotSetAddressAndPortNoMulticastSocket(e, config.getProtocolName(), addressProperty, config.getProtocolName(), portProperty, binding.getName());
        }
    }

    private static void setSocketBindingProperty(Introspector introspector, org.jgroups.conf.ProtocolConfiguration config, String name, String value) {
        try {
            Map<String, String> properties = config.getOriginalProperties();
            if (properties.containsKey(name)) {
                ROOT_LOGGER.unableToOverrideSocketBindingValue(name, config.getProtocolName(), value, properties.get(name));
            }
            setProperty(introspector, config, name, value);
        } catch (Exception e) {
            ROOT_LOGGER.unableToAccessProtocolPropertyValue(e, name, config.getProtocolName());
        }
    }

    private static void setProperty(Introspector introspector, org.jgroups.conf.ProtocolConfiguration config, String name, String value) {
        if (introspector.hasProperty(name)) {
            config.getProperties().put(name, value);
        }
    }

    /*
     * Collects the configurable properties for a given protocol.
     * This includes all fields and methods annotated with @Property for a given protocol
     */
    private static class Introspector {
        final Class<? extends Protocol> protocolClass;
        final Set<String> properties = new HashSet<>();

        Introspector(org.jgroups.conf.ProtocolConfiguration config) {
            String name = config.getProtocolName();
            try {
                this.protocolClass = config.getClassLoader().loadClass(name).asSubclass(Protocol.class);
                PrivilegedAction<Void> action = new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        Class<?> targetClass = Introspector.this.protocolClass;
                        while (Protocol.class.isAssignableFrom(targetClass)) {
                            for (Method method: targetClass.getDeclaredMethods()) {
                                if (method.isAnnotationPresent(Property.class)) {
                                    String property = method.getAnnotation(Property.class).name();
                                    if (!property.isEmpty()) {
                                        Introspector.this.properties.add(property);
                                    }
                                }
                            }
                            for (Field field: targetClass.getDeclaredFields()) {
                                if (field.isAnnotationPresent(Property.class)) {
                                    String property = field.getAnnotation(Property.class).name();
                                    Introspector.this.properties.add(!property.isEmpty() ? property : field.getName());
                                }
                            }
                            targetClass = targetClass.getSuperclass();
                        }
                        return null;
                    }
                };
                WildFlySecurityManager.doChecked(action);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
        }

        Class<? extends Protocol> getProtocolClass() {
            return this.protocolClass;
        }

        boolean hasProperty(String property) {
            return this.properties.contains(property);
        }
    }

    static class DefaultRouteStatusListener implements RouteStatusListener, Supplier<Set<String>> {

        private final Set<String> view = new ConcurrentSkipListSet<>();

        @Override
        public void sitesUp(String... sites) {
            JGroupsLogger log = JGroupsLogger.ROOT_LOGGER;
            if (log.isTraceEnabled())
                log.tracef("Joined x-site view: %s", Arrays.toString(sites));

            this.view.addAll(Arrays.asList(sites));
            log.receivedXSiteClusterView(this.view);
        }

        @Override
        public void sitesDown(String... sites) {
            JGroupsLogger log = JGroupsLogger.ROOT_LOGGER;
            if (log.isTraceEnabled())
                log.tracef("Left x-site view: %s", Arrays.toString(sites));

            this.view.removeAll(Arrays.asList(sites));
            log.receivedXSiteClusterView(this.view);
        }

        @Override
        public Set<String> get() {
            return Collections.unmodifiableSet(this.view);
        }

    }

}
