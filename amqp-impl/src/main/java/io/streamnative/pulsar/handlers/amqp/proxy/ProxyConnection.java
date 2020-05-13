/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.amqp.proxy;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.US_ASCII;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.streamnative.pulsar.handlers.amqp.AmqpBrokerDecoder;

import java.util.ArrayList;
import java.util.List;

import io.streamnative.pulsar.handlers.amqp.AmqpProtocolHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.qpid.server.QpidException;
import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.ProtocolVersion;
import org.apache.qpid.server.protocol.v0_8.AMQShortString;
import org.apache.qpid.server.protocol.v0_8.FieldTable;
import org.apache.qpid.server.protocol.v0_8.transport.AMQDataBlock;
import org.apache.qpid.server.protocol.v0_8.transport.AMQMethodBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionTuneBody;
import org.apache.qpid.server.protocol.v0_8.transport.MethodRegistry;
import org.apache.qpid.server.protocol.v0_8.transport.ProtocolInitiation;
import org.apache.qpid.server.protocol.v0_8.transport.ServerChannelMethodProcessor;
import org.apache.qpid.server.protocol.v0_8.transport.ServerMethodProcessor;

/**
 * Proxy connection.
 */
@Slf4j
public class ProxyConnection extends ChannelInboundHandlerAdapter implements
        ServerMethodProcessor<ServerChannelMethodProcessor> {

    private PulsarService pulsarService;
    private ProxyService proxyService;
    private ProxyConfiguration proxyConfig;
    @Getter
    private ChannelHandlerContext cnx;
    private State state;
    private NamespaceName namespaceName;
    private int amqpBrokerPort = 5672;
    private String amqpBrokerHost;
    private ProxyHandler proxyHandler;

    protected AmqpBrokerDecoder brokerDecoder;
    private MethodRegistry methodRegistry;
    private ProtocolVersion protocolVersion;
    private int currentClassId;
    private int currentMethodId;
    private LookupHandler lookupHandler;
    private String vhost;

    private List<Object> connectMsgList = new ArrayList<>();

    private enum State {
        Init,
        RedirectLookup,
        RedirectToBroker,
        Close
    }

    public ProxyConnection(ProxyService proxyService, PulsarService pulsarService) {
        log.info("ProxyConnection init ...");
        this.pulsarService = pulsarService;
        this.proxyService = proxyService;
        this.proxyConfig = proxyService.getProxyConfig();
        brokerDecoder = new AmqpBrokerDecoder(this);
        protocolVersion = ProtocolVersion.v0_91;
        methodRegistry = new MethodRegistry(protocolVersion);
        if (pulsarService != null) {
            lookupHandler = new PulsarServiceLookupHandler(pulsarService);
        }
        state = State.Init;
    }

    @Override
    public void channelActive(ChannelHandlerContext cnx) throws Exception {
        super.channelActive(cnx);
        this.cnx = cnx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.info("RedirectConnection [channelRead] - access msg: {}", ((ByteBuf) msg));
        switch (state) {
            case Init:
            case RedirectLookup:
                log.info("RedirectConnection [channelRead] - RedirectLookup");
                connectMsgList.add(msg);

                // Get a buffer that contains the full frame
                ByteBuf buffer = (ByteBuf) msg;

                io.netty.channel.Channel nettyChannel = ctx.channel();
                checkState(nettyChannel.equals(this.cnx.channel()));

                try {
                    brokerDecoder.decodeBuffer(QpidByteBuffer.wrap(buffer.nioBuffer()));
                } catch (Throwable e) {
                    log.error("error while handle command:", e);
                    close();
                }
                brokerDecoder.getMethodProcessor();

                break;
            case RedirectToBroker:
                log.info("RedirectConnection [channelRead] - RedirectToBroker");
                proxyHandler.getBrokerChannel().writeAndFlush(msg);
                break;
            case Close:
                log.info("RedirectConnection [channelRead] - closed");
                break;
            default:
                log.info("RedirectConnection [channelRead] - invalid state");
                break;
        }
    }

    // step 1
    @Override
    public void receiveProtocolHeader(ProtocolInitiation protocolInitiation) {
        if (log.isDebugEnabled()) {
            log.debug("RedirectConnection - [receiveProtocolHeader] Protocol Header [{}]", protocolInitiation);
        }
        brokerDecoder.setExpectProtocolInitiation(false);
        try {
            ProtocolVersion pv = protocolInitiation.checkVersion(); // Fails if not correct
            // TODO serverProperties mechanis
            AMQMethodBody responseBody = this.methodRegistry.createConnectionStartBody(
                    (short) protocolVersion.getMajorVersion(),
                    (short) pv.getActualMinorVersion(),
                    null,
                    // TODO temporary modification
                    "PLAIN".getBytes(US_ASCII),
                    "en_US".getBytes(US_ASCII));
            writeFrame(responseBody.generateFrame(0));
        } catch (QpidException e) {
            log.error("Received unsupported protocol initiation for protocol version: {} ", getProtocolVersion(), e);
        }
    }

    // step 2
    @Override
    public void receiveConnectionStartOk(FieldTable clientProperties, AMQShortString mechanism, byte[] response,
                                         AMQShortString locale) {
        if (log.isDebugEnabled()) {
            log.debug("RedirectConnection - [receiveConnectionStartOk] " +
                            "clientProperties: {}, mechanism: {}, locale: {}", clientProperties, mechanism, locale);
        }
        AMQMethodBody responseBody = this.methodRegistry.createConnectionSecureBody(new byte[0]);
        writeFrame(responseBody.generateFrame(0));
    }

    // step 3
    @Override
    public void receiveConnectionSecureOk(byte[] response) {
        if (log.isDebugEnabled()) {
            log.debug("RedirectConnection - [receiveConnectionSecureOk] response: {}", new String(response));
        }
        // TODO AUTH
        ConnectionTuneBody tuneBody =
                methodRegistry.createConnectionTuneBody(proxyConfig.getMaxNoOfChannels(),
                        proxyConfig.getMaxFrameSize(), proxyConfig.getHeartBeat());
        writeFrame(tuneBody.generateFrame(0));
    }

    // step 4
    @Override
    public void receiveConnectionTuneOk(int i, long l, int i1) {
        log.info("RedirectConnection - [receiveConnectionTuneOk]");
    }

    // step 5
    @Override
    public void receiveConnectionOpen(AMQShortString virtualHost, AMQShortString capabilities, boolean insist) {
        log.info("RedirectConnection - [receiveConnectionOpen] virtualHost: {}", virtualHost);
        if (log.isDebugEnabled()) {
            log.debug("RedirectConnection - [receiveConnectionOpen] virtualHost: {} capabilities: {} insist: {}",
                    virtualHost, capabilities, insist);
        }

        state = State.RedirectLookup;
        String virtualHostStr = AMQShortString.toString(virtualHost);
        if ((virtualHostStr != null) && virtualHostStr.charAt(0) == '/') {
            virtualHostStr = virtualHostStr.substring(1);
        }
        vhost = virtualHostStr;

        String amqpBrokerHost = "";
        int amqpBrokerPort = 0;
        if (proxyService.getVhostBrokerMap().containsKey(virtualHostStr)) {
            amqpBrokerHost = proxyService.getVhostBrokerMap().get(virtualHostStr).getLeft();
            amqpBrokerPort = proxyService.getVhostBrokerMap().get(virtualHostStr).getRight();
        } else {
            try {
                NamespaceName namespaceName = NamespaceName.get(proxyConfig.getAmqpTenant(), virtualHostStr);
                Pair<String, Integer> lookupData = lookupHandler.findBroker(namespaceName, AmqpProtocolHandler.PROTOCOL_NAME);
                amqpBrokerHost = lookupData.getLeft();
                amqpBrokerPort = lookupData.getRight();
                proxyService.getVhostBrokerMap().put(virtualHostStr, lookupData);
            } catch (Exception e) {
                log.error("Lookup broker failed.");
                return;
            }
        }

        try {
            if (StringUtils.isEmpty(amqpBrokerHost) || amqpBrokerPort == 0) {
                log.error("Lookup broker failed.");
                return;
            }
            proxyHandler = new ProxyHandler(vhost, proxyService,
                    this, amqpBrokerHost, amqpBrokerPort, connectMsgList);
            state = State.RedirectToBroker;
            AMQMethodBody responseBody = methodRegistry.createConnectionOpenOkBody(virtualHost);
            writeFrame(responseBody.generateFrame(0));
        } catch (Exception e) {
            log.error("Failed to lookup broker.", e);
        }
    }

    @Override
    public void receiveChannelOpen(int i) {
        log.info("RedirectConnection - [receiveChannelOpen]");
    }

    @Override
    public ProtocolVersion getProtocolVersion() {
        log.info("RedirectConnection - [getProtocolVersion]");
        return null;
    }

    @Override
    public ServerChannelMethodProcessor getChannelMethodProcessor(int i) {
        log.info("RedirectConnection - [getChannelMethodProcessor]");
        return null;
    }

    @Override
    public void receiveConnectionClose(int i, AMQShortString amqShortString, int i1, int i2) {
        log.info("RedirectConnection - [receiveConnectionClose]");
    }

    @Override
    public void receiveConnectionCloseOk() {
        log.info("RedirectConnection - [receiveConnectionCloseOk]");
    }

    @Override
    public void receiveHeartbeat() {
        log.info("RedirectConnection - [receiveHeartbeat]");
    }


    @Override
    public void setCurrentMethod(int classId, int methodId) {
        if (log.isDebugEnabled()) {
            log.debug("RedirectConnection - [setCurrentMethod] classId: {}, methodId: {}", classId, methodId);
        }
        currentClassId = classId;
        currentMethodId = methodId;
    }

    @Override
    public boolean ignoreAllButCloseOk() {
        log.info("RedirectConnection - [ignoreAllButCloseOk]");
        return false;
    }

    public synchronized void writeFrame(AMQDataBlock frame) {
        if (log.isDebugEnabled()) {
            log.debug("send: " + frame);
        }
        cnx.writeAndFlush(frame);
    }

    public String getAmqpBrokerUrl() {
        return amqpBrokerHost + ":" + amqpBrokerPort;
    }

    public void close() {

    }

}
