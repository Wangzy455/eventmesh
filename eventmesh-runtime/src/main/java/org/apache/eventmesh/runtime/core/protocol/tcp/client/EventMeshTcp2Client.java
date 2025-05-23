/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.core.protocol.tcp.client;

import static org.apache.eventmesh.common.protocol.tcp.Command.REDIRECT_TO_CLIENT;
import static org.apache.eventmesh.common.protocol.tcp.Command.SERVER_GOODBYE_REQUEST;

import org.apache.eventmesh.common.protocol.tcp.Header;
import org.apache.eventmesh.common.protocol.tcp.OPStatus;
import org.apache.eventmesh.common.protocol.tcp.Package;
import org.apache.eventmesh.common.protocol.tcp.RedirectInfo;
import org.apache.eventmesh.common.utils.IPUtils;
import org.apache.eventmesh.runtime.boot.TCPThreadPoolGroup;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.group.ClientSessionGroupMapping;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.session.Session;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.session.SessionState;
import org.apache.eventmesh.runtime.metrics.tcp.EventMeshTcpMetricsManager;
import org.apache.eventmesh.runtime.util.RemotingHelper;
import org.apache.eventmesh.runtime.util.Utils;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventMeshTcp2Client {

    public static InetSocketAddress serverGoodby2Client(TCPThreadPoolGroup tcpThreadPoolGroup,
        Session session,
        ClientSessionGroupMapping mapping) {
        log.info("serverGoodby2Client client[{}]", session.getClient());
        try {
            long startTime = System.currentTimeMillis();
            Package msg = new Package();
            msg.setHeader(new Header(SERVER_GOODBYE_REQUEST, OPStatus.SUCCESS.getCode(),
                "graceful normal quit from eventmesh", null));

            tcpThreadPoolGroup.getScheduler().submit(() -> {
                long taskExecuteTime = System.currentTimeMillis();
                Utils.writeAndFlush(msg, startTime, taskExecuteTime, session.getContext(), session);
            });
            InetSocketAddress address = (InetSocketAddress) session.getContext().channel().remoteAddress();

            closeSessionIfTimeout(tcpThreadPoolGroup, session, mapping);
            return address;
        } catch (Exception e) {
            log.error("exception occur while serverGoodby2Client", e);
            return null;
        }
    }

    public static InetSocketAddress goodBye2Client(TCPThreadPoolGroup tcpThreadPoolGroup, Session session,
        String errMsg, int eventMeshStatus,
        ClientSessionGroupMapping mapping) {
        try {
            long startTime = System.currentTimeMillis();
            Package msg = new Package();
            msg.setHeader(new Header(SERVER_GOODBYE_REQUEST, eventMeshStatus, errMsg, null));
            tcpThreadPoolGroup.getScheduler().schedule(() -> {
                long taskExecuteTime = System.currentTimeMillis();
                Utils.writeAndFlush(msg, startTime, taskExecuteTime, session.getContext(), session);
            }, 1 * 1000, TimeUnit.MILLISECONDS);

            closeSessionIfTimeout(tcpThreadPoolGroup, session, mapping);

            return session.getRemoteAddress();
        } catch (Exception e) {
            log.error("exception occur while goodbye2client", e);
            return null;
        }
    }

    public static void goodBye2Client(ChannelHandlerContext ctx, String errMsg, ClientSessionGroupMapping mapping,
        EventMeshTcpMetricsManager eventMeshTcpMetricsManager) {
        long startTime = System.currentTimeMillis();
        Package pkg = new Package(new Header(SERVER_GOODBYE_REQUEST, OPStatus.FAIL.getCode(), errMsg, null));
        eventMeshTcpMetricsManager.eventMesh2clientMsgNumIncrement(IPUtils.parseChannelRemoteAddr(ctx.channel()));
        log.info("goodBye2Client client[{}]", RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
        ctx.channel().eventLoop().execute(() -> {
            ctx.writeAndFlush(pkg).addListener((ChannelFutureListener) future -> {
                Utils.logSucceedMessageFlow(pkg, null, startTime, startTime);
                try {
                    mapping.closeSession(ctx);
                } catch (Exception e) {
                    log.warn("close session failed!", e);
                }
            });
        });
    }

    public static String redirectClient2NewEventMesh(TCPThreadPoolGroup tcpThreadPoolGroup, String newEventMeshIp,
        int port, Session session, ClientSessionGroupMapping mapping) {
        log.info("begin to gracefully redirect Client {}, newIPPort[{}]", session.getClient(), newEventMeshIp + ":"
            + port);
        try {
            long startTime = System.currentTimeMillis();

            Package pkg = new Package();
            pkg.setHeader(new Header(REDIRECT_TO_CLIENT, OPStatus.SUCCESS.getCode(), null, null));
            pkg.setBody(new RedirectInfo(newEventMeshIp, port));
            tcpThreadPoolGroup.getScheduler().schedule(() -> {
                long taskExecuteTime = System.currentTimeMillis();
                Utils.writeAndFlush(pkg, startTime, taskExecuteTime, session.getContext(), session);
            }, 5 * 1000, TimeUnit.MILLISECONDS);
            closeSessionIfTimeout(tcpThreadPoolGroup, session, mapping);
            return session.getRemoteAddress() + "--->" + newEventMeshIp + ":" + port;
        } catch (Exception e) {
            log.error("exception occur while redirectClient2NewEventMesh", e);
            return null;
        }
    }

    public static void closeSessionIfTimeout(TCPThreadPoolGroup tcpThreadPoolGroup, Session session,
        ClientSessionGroupMapping mapping) {
        tcpThreadPoolGroup.getScheduler().schedule(() -> {
            try {
                if (session.getSessionState() != SessionState.CLOSED) {
                    mapping.closeSession(session.getContext());
                    log.info("closeSessionIfTimeout success, session[{}]", session.getClient());
                }
            } catch (Exception e) {
                log.error("close session failed", e);
            }
        }, 30 * 1000, TimeUnit.MILLISECONDS);
    }
}
