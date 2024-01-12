package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.common.CommonGbChannel;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.SIPSender;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommanderFroPlatform;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.ISIPRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.media.zlm.ZLMMediaListManager;
import com.genersoft.iot.vmp.media.zlm.ZLMServerFactory;
import com.genersoft.iot.vmp.media.zlm.ZlmHttpHookSubscribe;
import com.genersoft.iot.vmp.media.zlm.dto.*;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OnStreamChangedHookParam;
import com.genersoft.iot.vmp.service.*;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.service.bean.MessageForPushChannel;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.service.redisMsg.RedisGbPlayMsgListener;
import com.genersoft.iot.vmp.service.redisMsg.RedisPushStreamResponseListener;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import gov.nist.javax.sdp.TimeDescriptionImpl;
import gov.nist.javax.sdp.fields.TimeField;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sdp.*;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Response;
import java.text.ParseException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Vector;

/**
 * SIP命令类型： INVITE请求
 */
@SuppressWarnings("rawtypes")
@Component
public class InviteRequestProcessor extends SIPRequestProcessorParent implements InitializingBean, ISIPRequestProcessor {

    private final static Logger logger = LoggerFactory.getLogger(InviteRequestProcessor.class);

    private final String method = "INVITE";

    @Autowired
    private SIPCommanderFroPlatform cmderFroPlatform;

    @Autowired
    private IVideoManagerStorage storager;

    @Autowired
    private IPlatformChannelService platformChannelService;

    @Autowired
    private IStreamPushService streamPushService;
    @Autowired
    private IStreamProxyService streamProxyService;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Autowired
    private SSRCFactory ssrcFactory;

    @Autowired
    private DynamicTask dynamicTask;

    @Autowired
    private RedisPushStreamResponseListener redisPushStreamResponseListener;

    @Autowired
    private IPlayService playService;

    @Autowired
    private Map<String, IResourceService> resourceServiceMap;

    @Autowired
    private SIPSender sipSender;

    @Autowired
    private ZLMServerFactory zlmServerFactory;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private ZlmHttpHookSubscribe zlmHttpHookSubscribe;

    @Autowired
    private SIPProcessorObserver sipProcessorObserver;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private ZLMMediaListManager mediaListManager;


    @Autowired
    private RedisGbPlayMsgListener redisGbPlayMsgListener;


    @Override
    public void afterPropertiesSet() throws Exception {
        // 添加消息处理的订阅
        sipProcessorObserver.addRequestProcessor(method, this);
    }

    /**
     * 处理invite请求
     *
     * @param evt 请求消息
     */
    @Override
    public void process(RequestEvent evt) {
        //  Invite Request消息实现，此消息一般为级联消息，上级给下级发送请求视频指令
        try {
            SIPRequest request = (SIPRequest)evt.getRequest();
            String channelId = SipUtils.getChannelIdFromRequest(request);
            String requesterId = SipUtils.getUserIdFromFromHeader(request);
            CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
            if (requesterId == null || channelId == null) {
                logger.info("无法从请求中获取到平台id，返回400");
                // 参数不全， 发400，请求错误
                try {
                    responseAck(request, Response.BAD_REQUEST);
                } catch (SipException | InvalidArgumentException | ParseException e) {
                    logger.error("[命令发送失败] invite BAD_REQUEST: {}", e.getMessage());
                }
                return;
            }

            // 查询请求是否来自上级平台\设备
            ParentPlatform platform = storager.queryParentPlatByServerGBId(requesterId);
            if (platform == null) {
                inviteFromDeviceHandle(request, requesterId);

            } else {
                // 查询平台下是否有该通道
                CommonGbChannel channel = platformChannelService.queryChannelByPlatformIdAndChannelDeviceId(platform.getId(), channelId);
                if (channel == null) {
                    logger.info("[国标级联] 上级点播 通道不存在，返回404: {}", channelId);
                    try {
                        // 通道不存在，发404，资源不存在
                        responseAck(request, Response.NOT_FOUND);
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 通道不存在: {}", e.getMessage());
                    }
                    return;
                }
                IResourceService resourceService = resourceServiceMap.get(channel.getType());
                if (resourceService == null) {
                    logger.info("[国标级联] 上级点播 未找到类型{}的处理类: {}", channel.getType(), channelId);
                    try {
                        // 通道不存在，发404，资源不存在
                        responseAck(request, Response.NOT_IMPLEMENTED);
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 通道不存在: {}", e.getMessage());
                    }
                    return;
                }
                // 通道存在，发100，TRYING
                try {
                    responseAck(request, Response.TRYING);
                } catch (SipException | InvalidArgumentException | ParseException e) {
                    logger.error("[命令发送失败] invite TRYING: {}", e.getMessage());
                }

                // 解析sdp消息, 使用jainsip 自带的sdp解析方式
                String contentString = new String(request.getRawContent());

                Gb28181Sdp gb28181Sdp = SipUtils.parseSDP(contentString);

                if (gb28181Sdp.getSdpPort() == 0) {
                    logger.info("不支持的媒体格式，返回415");
                    // 回复不支持的格式
                    try {
                        // 不支持的格式，发415
                        responseAck(request, Response.UNSUPPORTED_MEDIA_TYPE);
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 不支持的格式: {}", e.getMessage());
                    }
                    return;
                }
                String sessionName = gb28181Sdp.getBaseSdb().getSessionName().getValue();
                String streamTypeStr = "UDP";
                if (gb28181Sdp.isTcp()) {
                    if (gb28181Sdp.isTcpActive()) {
                        streamTypeStr = "TCP-ACTIVE";
                    }else {
                        streamTypeStr = "TCP-PASSIVE";
                    }
                }
                logger.info("[上级Invite] {}, 平台：{}， 通道：{}, 收流地址：{}:{}，收流方式：{}, ssrc：{}",
                        sessionName, gb28181Sdp.getUsername(), channelId, gb28181Sdp.getAddressStr(),
                        gb28181Sdp.getSdpPort(), streamTypeStr, gb28181Sdp.getSsrc());

                IResourcePlayCallback callback = (commonGbChannel, mediaServerItem,  code, message, streamInfo) -> {
                    if (code != 0) {
                        logger.info("[上级Invite] 获取资源流失败。{}, {}/{}", message, streamInfo.getApp(), streamInfo.getStream());
                        try {
                            cmderFroPlatform.streamByeCmd(platform, callIdHeader.getCallId());
                        } catch (SipException | InvalidArgumentException | ParseException e) {
                            logger.error("[命令发送失败] 国标级联 发送BYE: {}", e.getMessage());
                        }
                        return;
                    }
                    try {
                        String ssrc;
                        logger.info("[上级Invite] 收到资源推流。 回复200OK(SDP)， {}/{}", streamInfo.getApp(), streamInfo.getStream());
                        if (userSetting.getUseCustomSsrcForParentInvite() || gb28181Sdp.getSsrc() == null) {
                            // 上级平台点播时不使用上级平台指定的ssrc，使用自定义的ssrc，参考国标文档-点播外域设备媒体流SSRC处理方式
                            ssrc = "Play".equalsIgnoreCase(sessionName) ?
                                    ssrcFactory.getPlaySsrc(mediaServerItem.getId()) :
                                    ssrcFactory.getPlayBackSsrc(mediaServerItem.getId());
                        }else {
                            ssrc = gb28181Sdp.getSsrc();
                        }

                        SendRtpItem sendRtpItem = zlmServerFactory.createSendRtpItem(mediaServerItem,
                                gb28181Sdp.getAddressStr(), gb28181Sdp.getSdpPort(), ssrc, requesterId,
                                channelId, gb28181Sdp.isTcp(), platform.isRtcp());
                        if (sendRtpItem == null) {
                            logger.warn("[上级Invite] 获取发流端口资源失败 服务器端口资源可能不足");
                            try {
                                responseAck(request, Response.BUSY_HERE);
                            } catch (SipException | InvalidArgumentException | ParseException e) {
                                logger.error("[命令发送失败] invite 服务器端口资源不足: {}", e.getMessage());
                            }
                            return;
                        }
                        sendRtpItem.setTcpActive(gb28181Sdp.isTcpActive());
                        sendRtpItem.setCallId(callIdHeader.getCallId());
                        if ("Play".equalsIgnoreCase(sessionName)) {
                            sendRtpItem.setPlayType(InviteStreamType.PLAY);
                        }else if ("Playback".equalsIgnoreCase(sessionName)) {
                            sendRtpItem.setPlayType(InviteStreamType.PLAYBACK);
                        }else if ("Download".equalsIgnoreCase(sessionName)) {
                            sendRtpItem.setPlayType(InviteStreamType.DOWNLOAD);
                        }
                        sendRtpItem.setStatus(1);
                        redisCatchStorage.updateSendRTPSever(sendRtpItem);

                        StringBuffer content = new StringBuffer(200);
                        content.append("v=0\r\n");
                        content.append("o=" + channelId + " 0 0 IN IP4 " + mediaServerItem.getSdpIp() + "\r\n");
                        content.append("s=" + sessionName + "\r\n");
                        content.append("c=IN IP4 " + mediaServerItem.getSdpIp() + "\r\n");
                        if (!"Play".equalsIgnoreCase(sessionName)) {
                            content.append("t=" + gb28181Sdp.getStartTime() + " " + gb28181Sdp.getStopTime() + "\r\n");
                        } else {
                            content.append("t=0 0\r\n");
                        }
                        content.append("m=video " + sendRtpItem.getLocalPort() + " RTP/AVP 96\r\n");
                        content.append("a=sendonly\r\n");
                        content.append("a=rtpmap:96 PS/90000\r\n");
                        content.append("y=" + sendRtpItem.getSsrc() + "\r\n");
                        content.append("f=\r\n");

                        // 超时未收到Ack应该回复bye,当前等待时间为10秒
                        dynamicTask.startDelay(callIdHeader.getCallId(), () -> {
                            logger.info("Ack 等待超时");
                            mediaServerService.releaseSsrc(mediaServerItem.getId(), sendRtpItem.getSsrc());
                            // 回复bye
                            try {
                                cmderFroPlatform.streamByeCmd(platform, callIdHeader.getCallId());
                            } catch (SipException | InvalidArgumentException | ParseException e) {
                                logger.error("[命令发送失败] 国标级联 发送BYE: {}", e.getMessage());
                            }
                        }, 60 * 1000);
                        responseSdpAck(request, content.toString(), platform);
                        if (sendRtpItem.isTcpActive()) {
                            MediaServerItem mediaInfo = mediaServerService.getOne(sendRtpItem.getMediaServerId());
                            Map<String, Object> param = new HashMap<>(12);
                            param.put("vhost","__defaultVhost__");
                            param.put("app",sendRtpItem.getApp());
                            param.put("stream",sendRtpItem.getStreamId());
                            param.put("ssrc", sendRtpItem.getSsrc());
                            if (!sendRtpItem.isTcpActive()) {
                                param.put("dst_url",sendRtpItem.getIp());
                                param.put("dst_port", sendRtpItem.getPort());
                            }
                            String is_Udp = sendRtpItem.isTcp() ? "0" : "1";
                            param.put("is_udp", is_Udp);
                            param.put("src_port", sendRtpItem.getLocalPort());
                            param.put("pt", sendRtpItem.getPt());
                            param.put("use_ps", sendRtpItem.isUsePs() ? "1" : "0");
                            param.put("only_audio", sendRtpItem.isOnlyAudio() ? "1" : "0");
                            if (!sendRtpItem.isTcp()) {
                                // 开启rtcp保活
                                param.put("udp_rtcp_timeout", sendRtpItem.isRtcp()? "1":"0");
                            }
                            JSONObject startSendRtpStreamResult = zlmServerFactory.startSendRtpStreamForPassive(mediaInfo, param);
                            if (startSendRtpStreamResult != null) {
                                startSendRtpStreamHand(evt, sendRtpItem, null, startSendRtpStreamResult, param, callIdHeader);
                            }
                        }
                    }catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] 国标级联 回复SdpAck", e);
                    }
                };
                if ("Play".equalsIgnoreCase(sessionName)) {
                    resourceService.startPlay(channel, callback);
                }else if ("Playback".equalsIgnoreCase(sessionName)) {
                    resourceService.startPlayback(channel, gb28181Sdp.getStartTime(), gb28181Sdp.getStopTime(), callback);
                }else if ("Download".equalsIgnoreCase(sessionName)) {
                    resourceService.startDownload(channel, gb28181Sdp.getStartTime(), gb28181Sdp.getStopTime(),
                            gb28181Sdp.getDownloadSpeed(), callback);
                }




//
//                Device device = null;
//                // 通过 channel 和 gbStream 是否为null 值判断来源是直播流合适国标
//                if (channel != null) {
//
//                    ErrorCallback<Object> hookEvent = (code, msg, data) -> {
//                        StreamInfo streamInfo = (StreamInfo)data;
//                        MediaServerItem mediaServerItemInUSe = mediaServerService.getOne(streamInfo.getMediaServerId());
//                        logger.info("[上级Invite]下级已经开始推流。 回复200OK(SDP)， {}/{}", streamInfo.getApp(), streamInfo.getStream());
//                        //     * 0 等待设备推流上来
//                        //     * 1 下级已经推流，等待上级平台回复ack
//                        //     * 2 推流中
//                        sendRtpItem.setStatus(1);
//                        redisCatchStorage.updateSendRTPSever(sendRtpItem);
//
//
//
//
//                        try {
//                            // 超时未收到Ack应该回复bye,当前等待时间为10秒
//                            dynamicTask.startDelay(callIdHeader.getCallId(), () -> {
//                                logger.info("Ack 等待超时");
//                                mediaServerService.releaseSsrc(mediaServerItemInUSe.getId(), sendRtpItem.getSsrc());
//                                // 回复bye
//                                try {
//                                    cmderFroPlatform.streamByeCmd(platform, callIdHeader.getCallId());
//                                } catch (SipException | InvalidArgumentException | ParseException e) {
//                                    logger.error("[命令发送失败] 国标级联 发送BYE: {}", e.getMessage());
//                                }
//                            }, 60 * 1000);
//                            responseSdpAck(request, content.toString(), platform);
//                            // tcp主动模式，回复sdp后开启监听
//                            if (sendRtpItem.isTcpActive()) {
//                                MediaServerItem mediaInfo = mediaServerService.getOne(sendRtpItem.getMediaServerId());
//                                Map<String, Object> param = new HashMap<>(12);
//                                param.put("vhost","__defaultVhost__");
//                                param.put("app",sendRtpItem.getApp());
//                                param.put("stream",sendRtpItem.getStreamId());
//                                param.put("ssrc", sendRtpItem.getSsrc());
//                                if (!sendRtpItem.isTcpActive()) {
//                                    param.put("dst_url",sendRtpItem.getIp());
//                                    param.put("dst_port", sendRtpItem.getPort());
//                                }
//                                String is_Udp = sendRtpItem.isTcp() ? "0" : "1";
//                                param.put("is_udp", is_Udp);
//                                param.put("src_port", localPort);
//                                param.put("pt", sendRtpItem.getPt());
//                                param.put("use_ps", sendRtpItem.isUsePs() ? "1" : "0");
//                                param.put("only_audio", sendRtpItem.isOnlyAudio() ? "1" : "0");
//                                if (!sendRtpItem.isTcp()) {
//                                    // 开启rtcp保活
//                                    param.put("udp_rtcp_timeout", sendRtpItem.isRtcp()? "1":"0");
//                                }
//                                JSONObject startSendRtpStreamResult = zlmServerFactory.startSendRtpStreamForPassive(mediaInfo, param);
//                                if (startSendRtpStreamResult != null) {
//                                    startSendRtpStreamHand(evt, sendRtpItem, null, startSendRtpStreamResult, param, callIdHeader);
//                                }
//                            }
//                        } catch (SipException | InvalidArgumentException | ParseException e) {
//                            logger.error("[命令发送失败] 国标级联 回复SdpAck", e);
//                        }
//                    };
//                    ErrorCallback<Object> errorEvent = ((statusCode, msg, data) -> {
//                        // 未知错误。直接转发设备点播的错误
//                        try {
//                            if (statusCode > 0) {
//                                Response response = getMessageFactory().createResponse(statusCode, evt.getRequest());
//                                sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
//                            }
//                        } catch (ParseException | SipException  e) {
//                            logger.error("未处理的异常 ", e);
//                        }
//                    });
//                    sendRtpItem.setApp("rtp");
//                    if ("Playback".equalsIgnoreCase(sessionName)) {
//                        sendRtpItem.setPlayType(InviteStreamType.PLAYBACK);
//                        String startTimeStr = DateUtil.urlFormatter.format(start);
//                        String endTimeStr = DateUtil.urlFormatter.format(end);
//                        String stream = device.getDeviceId() + "_" + channelId + "_" + startTimeStr + "_" + endTimeStr;
//                        SSRCInfo ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, stream, null, device.isSsrcCheck(), true, 0, false, device.getStreamModeForParam());
//                        sendRtpItem.setStreamId(ssrcInfo.getStream());
//                        // 写入redis， 超时时回复
//                        redisCatchStorage.updateSendRTPSever(sendRtpItem);
//                        playService.playBack(mediaServerItem, ssrcInfo, device.getDeviceId(), channelId, DateUtil.formatter.format(start),
//                                DateUtil.formatter.format(end),
//                                (code, msg, data) -> {
//                                    if (code == InviteErrorCode.SUCCESS.getCode()){
//                                        hookEvent.run(code, msg, data);
//                                    }else if (code == InviteErrorCode.ERROR_FOR_SIGNALLING_TIMEOUT.getCode() || code == InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode()){
//                                        logger.info("[录像回放]超时, 用户：{}， 通道：{}", username, channelId);
//                                        redisCatchStorage.deleteSendRTPServer(platform.getServerGBId(), channelId, callIdHeader.getCallId(), null);
//                                        errorEvent.run(code, msg, data);
//                                    }else {
//                                        errorEvent.run(code, msg, data);
//                                    }
//                                });
//                    } else if ("Download".equalsIgnoreCase(sessionName)) {
//                        // 获取指定的下载速度
//                        Vector sdpMediaDescriptions = sdp.getMediaDescriptions(true);
//                        MediaDescription mediaDescription = null;
//                        String downloadSpeed = "1";
//                        if (sdpMediaDescriptions.size() > 0) {
//                            mediaDescription = (MediaDescription) sdpMediaDescriptions.get(0);
//                        }
//                        if (mediaDescription != null) {
//                            downloadSpeed = mediaDescription.getAttribute("downloadspeed");
//                        }
//
//                        sendRtpItem.setPlayType(InviteStreamType.DOWNLOAD);
//                        SSRCInfo ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, null, null, device.isSsrcCheck(), true, 0, false, device.getStreamModeForParam());
//                        sendRtpItem.setStreamId(ssrcInfo.getStream());
//                        // 写入redis， 超时时回复
//                        redisCatchStorage.updateSendRTPSever(sendRtpItem);
//                        playService.download(mediaServerItem, ssrcInfo, device.getDeviceId(), channelId, DateUtil.formatter.format(start),
//                                DateUtil.formatter.format(end), Integer.parseInt(downloadSpeed),
//                                (code, msg, data) -> {
//                                    if (code == InviteErrorCode.SUCCESS.getCode()) {
//                                        hookEvent.run(code, msg, data);
//                                    } else if (code == InviteErrorCode.ERROR_FOR_SIGNALLING_TIMEOUT.getCode() || code == InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode()) {
//                                        logger.info("[录像下载]超时, 用户：{}， 通道：{}", username, channelId);
//                                        redisCatchStorage.deleteSendRTPServer(platform.getServerGBId(), channelId, callIdHeader.getCallId(), null);
//                                        errorEvent.run(code, msg, data);
//                                    } else {
//                                        errorEvent.run(code, msg, data);
//                                    }
//                                });
//                    } else {
//
//                        SSRCInfo ssrcInfo = playService.play(mediaServerItem, device.getDeviceId(), channelId, ssrc, ((code, msg, data) -> {
//                            if (code == InviteErrorCode.SUCCESS.getCode()) {
//                                hookEvent.run(code, msg, data);
//                            } else if (code == InviteErrorCode.ERROR_FOR_SIGNALLING_TIMEOUT.getCode() || code == InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode()) {
//                                logger.info("[上级点播]超时, 用户：{}， 通道：{}", username, channelId);
//                                redisCatchStorage.deleteSendRTPServer(platform.getServerGBId(), channelId, callIdHeader.getCallId(), null);
//                                errorEvent.run(code, msg, data);
//                            } else {
//                                errorEvent.run(code, msg, data);
//                            }
//                        }));
//                        sendRtpItem.setPlayType(InviteStreamType.PLAY);
//                        String streamId = String.format("%s_%s", device.getDeviceId(), channelId);
//                        sendRtpItem.setStreamId(streamId);
//                        sendRtpItem.setSsrc(ssrcInfo.getSsrc());
//                        redisCatchStorage.updateSendRTPSever(sendRtpItem);
//
//                    }
//                } else if (gbStream != null) {
//
//                    String ssrc;
//                    if (userSetting.getUseCustomSsrcForParentInvite() || gb28181Sdp.getSsrc() == null) {
//                        // 上级平台点播时不使用上级平台指定的ssrc，使用自定义的ssrc，参考国标文档-点播外域设备媒体流SSRC处理方式
//                        ssrc = "Play".equalsIgnoreCase(sessionName) ? ssrcFactory.getPlaySsrc(mediaServerItem.getId()) : ssrcFactory.getPlayBackSsrc(mediaServerItem.getId());
//                    }else {
//                        ssrc = gb28181Sdp.getSsrc();
//                    }
//
//                    if("push".equals(gbStream.getStreamType())) {
//                        if (streamPushItem != null && streamPushItem.isPushIng()) {
//                            // 推流状态
//                            pushStream(evt, request, gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
//                                    mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
//                        } else {
//                            // 未推流 拉起
//                            notifyStreamOnline(evt, request,gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
//                                    mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
//                        }
//                    }else if ("proxy".equals(gbStream.getStreamType())){
//                        if (null != proxyByAppAndStream) {
//                            if(proxyByAppAndStream.isStatus()){
//                                pushProxyStream(evt, request, gbStream,  platform, callIdHeader, mediaServerItem, port, tcpActive,
//                                        mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
//                            }else{
//                                //开启代理拉流
//                                notifyStreamOnline(evt, request,gbStream, null, platform, callIdHeader, mediaServerItem, port, tcpActive,
//                                        mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
//                            }
//                        }
//
//
//                    }
//                }
            }
        } catch (SdpParseException e) {
            logger.error("sdp解析错误", e);
        } catch (SdpException e) {
            logger.error("未处理的异常 ", e);
        }
    }

    private void startSendRtpStreamHand(RequestEvent evt, SendRtpItem sendRtpItem, ParentPlatform parentPlatform,
                                        JSONObject jsonObject, Map<String, Object> param, CallIdHeader callIdHeader) {
        if (jsonObject == null) {
            logger.error("下级TCP被动启动监听失败: 请检查ZLM服务");
        } else if (jsonObject.getInteger("code") == 0) {
            logger.info("调用ZLM-TCP被动推流接口, 结果： {}",  jsonObject);
            logger.info("启动监听TCP被动推流成功[ {}/{} ]，{}->{}:{}, " ,param.get("app"), param.get("stream"), jsonObject.getString("local_port"), param.get("dst_url"), param.get("dst_port"));
        } else {
            logger.error("启动监听TCP被动推流失败: {}, 参数：{}",jsonObject.getString("msg"), JSON.toJSONString(param));
        }
    }

    /**
     * 安排推流
     */
    private void pushProxyStream(RequestEvent evt, SIPRequest request, GbStream gbStream, ParentPlatform platform,
                            CallIdHeader callIdHeader, MediaServerItem mediaServerItem,
                            int port, Boolean tcpActive, boolean mediaTransmissionTCP,
                            String channelId, String addressStr, String ssrc, String requesterId) {
            Boolean streamReady = zlmServerFactory.isStreamReady(mediaServerItem, gbStream.getApp(), gbStream.getStream());
            if (streamReady != null && streamReady) {
                // 自平台内容
                SendRtpItem sendRtpItem = zlmServerFactory.createSendRtpItem(mediaServerItem, addressStr, port, ssrc, requesterId,
                        gbStream.getApp(), gbStream.getStream(), channelId, mediaTransmissionTCP, platform.isRtcp());

                if (sendRtpItem == null) {
                    logger.warn("服务器端口资源不足");
                    try {
                        responseAck(request, Response.BUSY_HERE);
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 服务器端口资源不足: {}", e.getMessage());
                    }
                    return;
                }
                if (tcpActive != null) {
                    sendRtpItem.setTcpActive(tcpActive);
                }
                sendRtpItem.setPlayType(InviteStreamType.PUSH);
                // 写入redis， 超时时回复
                sendRtpItem.setStatus(1);
                sendRtpItem.setCallId(callIdHeader.getCallId());
                sendRtpItem.setFromTag(request.getFromTag());

                SIPResponse response = sendStreamAck(mediaServerItem, request, sendRtpItem, platform, evt);
                if (response != null) {
                    sendRtpItem.setToTag(response.getToTag());
                }
                redisCatchStorage.updateSendRTPSever(sendRtpItem);
        }
    }
    private void pushStream(RequestEvent evt, SIPRequest request, GbStream gbStream, StreamPush streamPushItem, ParentPlatform platform,
                            CallIdHeader callIdHeader, MediaServerItem mediaServerItem,
                            int port, Boolean tcpActive, boolean mediaTransmissionTCP,
                            String channelId, String addressStr, String ssrc, String requesterId) {
        // 推流
        if (streamPushItem.isSelf()) {
            Boolean streamReady = zlmServerFactory.isStreamReady(mediaServerItem, gbStream.getApp(), gbStream.getStream());
            if (streamReady != null && streamReady) {
                // 自平台内容
                SendRtpItem sendRtpItem = zlmServerFactory.createSendRtpItem(mediaServerItem, addressStr, port, ssrc, requesterId,
                        gbStream.getApp(), gbStream.getStream(), channelId, mediaTransmissionTCP, platform.isRtcp());

                if (sendRtpItem == null) {
                    logger.warn("服务器端口资源不足");
                    try {
                        responseAck(request, Response.BUSY_HERE);
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 服务器端口资源不足: {}", e.getMessage());
                    }
                    return;
                }
                if (tcpActive != null) {
                    sendRtpItem.setTcpActive(tcpActive);
                }
                sendRtpItem.setPlayType(InviteStreamType.PUSH);
                // 写入redis， 超时时回复
                sendRtpItem.setStatus(1);
                sendRtpItem.setCallId(callIdHeader.getCallId());

                sendRtpItem.setFromTag(request.getFromTag());
                SIPResponse response = sendStreamAck(mediaServerItem, request, sendRtpItem, platform, evt);
                if (response != null) {
                    sendRtpItem.setToTag(response.getToTag());
                }

                redisCatchStorage.updateSendRTPSever(sendRtpItem);

            } else {
                // 不在线 拉起
                notifyStreamOnline(evt, request,gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
                        mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
            }

        } else {
            // 其他平台内容
            otherWvpPushStream(evt, request, gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
                    mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
        }
    }
    /**
     * 通知流上线
     */
    private void notifyStreamOnline(RequestEvent evt, SIPRequest request, GbStream gbStream, StreamPush streamPushItem, ParentPlatform platform,
                                    CallIdHeader callIdHeader, MediaServerItem mediaServerItem,
                                    int port, Boolean tcpActive, boolean mediaTransmissionTCP,
                                    String channelId, String addressStr, String ssrc, String requesterId) {
        if ("proxy".equals(gbStream.getStreamType())) {
            // TODO 控制启用以使设备上线
            logger.info("[ app={}, stream={} ]通道未推流，启用流后开始推流", gbStream.getApp(), gbStream.getStream());
            // 监听流上线
            HookSubscribeForStreamChange hookSubscribe = HookSubscribeFactory.on_stream_changed(gbStream.getApp(), gbStream.getStream(), true, "rtsp", mediaServerItem.getId());
            zlmHttpHookSubscribe.addSubscribe(hookSubscribe, (mediaServerItemInUSe, hookParam) -> {
                OnStreamChangedHookParam streamChangedHookParam = (OnStreamChangedHookParam)hookParam;
                logger.info("[上级点播]拉流代理已经就绪， {}/{}", streamChangedHookParam.getApp(), streamChangedHookParam.getStream());
                dynamicTask.stop(callIdHeader.getCallId());
                pushProxyStream(evt, request, gbStream,  platform, callIdHeader, mediaServerItem, port, tcpActive,
                        mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
            });
            dynamicTask.startDelay(callIdHeader.getCallId(), () -> {
                logger.info("[ app={}, stream={} ] 等待拉流代理流超时", gbStream.getApp(), gbStream.getStream());
                zlmHttpHookSubscribe.removeSubscribe(hookSubscribe);
            }, userSetting.getPlatformPlayTimeout());
            streamProxyService.start(gbStream.getApp(), gbStream.getStream(), (code, msg, data) -> {
                if (code != ErrorCode.SUCCESS.getCode()) {
                    try {
                        responseAck(request, Response.BUSY_HERE, "channel [" + gbStream.getGbId() + "] offline");
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 通道未推流: {}", e.getMessage());
                    }
                    zlmHttpHookSubscribe.removeSubscribe(hookSubscribe);
                    dynamicTask.stop(callIdHeader.getCallId());
                }
            });
        } else if ("push".equals(gbStream.getStreamType())) {
            if (!platform.isStartOfflinePush()) {
                // 平台设置中关闭了拉起离线的推流则直接回复
                try {
                    logger.info("[上级点播] 失败，推流设备未推流，channel: {}, app: {}, stream: {}", gbStream.getGbId(), gbStream.getApp(), gbStream.getStream());
                    responseAck(request, Response.TEMPORARILY_UNAVAILABLE, "channel stream not pushing");
                } catch (SipException | InvalidArgumentException | ParseException e) {
                    logger.error("[命令发送失败] invite 通道未推流: {}", e.getMessage());
                }
                return;
            }
            // 发送redis消息以使设备上线
            logger.info("[ app={}, stream={} ]通道未推流，发送redis信息控制设备开始推流", gbStream.getApp(), gbStream.getStream());

            MessageForPushChannel messageForPushChannel = MessageForPushChannel.getInstance(1,
                    gbStream.getApp(), gbStream.getStream(), gbStream.getGbId(), gbStream.getPlatformId(),
                    platform.getName(), null, gbStream.getMediaServerId());
            redisCatchStorage.sendStreamPushRequestedMsg(messageForPushChannel);
            // 设置超时
            dynamicTask.startDelay(callIdHeader.getCallId(), () -> {
                logger.info("[ app={}, stream={} ] 等待设备开始推流超时", gbStream.getApp(), gbStream.getStream());
                try {
                    redisPushStreamResponseListener.removeEvent(gbStream.getApp(), gbStream.getStream());
                    mediaListManager.removedChannelOnlineEventLister(gbStream.getApp(), gbStream.getStream());
                    responseAck(request, Response.REQUEST_TIMEOUT); // 超时
                } catch (SipException | InvalidArgumentException | ParseException e) {
                    logger.error("未处理的异常 ", e);
                }
            }, userSetting.getPlatformPlayTimeout());
            // 添加监听
            int finalPort = port;
            Boolean finalTcpActive = tcpActive;

            // 添加在本机上线的通知
            mediaListManager.addChannelOnlineEventLister(gbStream.getApp(), gbStream.getStream(), (app, stream, serverId) -> {
                dynamicTask.stop(callIdHeader.getCallId());
                redisPushStreamResponseListener.removeEvent(gbStream.getApp(), gbStream.getStream());
                if (serverId.equals(userSetting.getServerId())) {
                    SendRtpItem sendRtpItem = zlmServerFactory.createSendRtpItem(mediaServerItem, addressStr, finalPort, ssrc, requesterId,
                            app, stream, channelId, mediaTransmissionTCP, platform.isRtcp());

                    if (sendRtpItem == null) {
                        logger.warn("上级点时创建sendRTPItem失败，可能是服务器端口资源不足");
                        try {
                            responseAck(request, Response.BUSY_HERE);
                        } catch (SipException e) {
                            logger.error("未处理的异常 ", e);
                        } catch (InvalidArgumentException e) {
                            logger.error("未处理的异常 ", e);
                        } catch (ParseException e) {
                            logger.error("未处理的异常 ", e);
                        }
                        return;
                    }
                    if (finalTcpActive != null) {
                        sendRtpItem.setTcpActive(finalTcpActive);
                    }
                    sendRtpItem.setPlayType(InviteStreamType.PUSH);
                    // 写入redis， 超时时回复
                    sendRtpItem.setStatus(1);
                    sendRtpItem.setCallId(callIdHeader.getCallId());

                    sendRtpItem.setFromTag(request.getFromTag());
                    SIPResponse response = sendStreamAck(mediaServerItem, request, sendRtpItem, platform, evt);
                    if (response != null) {
                        sendRtpItem.setToTag(response.getToTag());
                    }
                    redisCatchStorage.updateSendRTPSever(sendRtpItem);
                } else {
                    // 其他平台内容
                    otherWvpPushStream(evt, request, gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
                            mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
                }
            });

            // 添加回复的拒绝或者错误的通知
            redisPushStreamResponseListener.addEvent(gbStream.getApp(), gbStream.getStream(), response -> {
                if (response.getCode() != 0) {
                    dynamicTask.stop(callIdHeader.getCallId());
                    mediaListManager.removedChannelOnlineEventLister(gbStream.getApp(), gbStream.getStream());
                    try {
                        responseAck(request, Response.TEMPORARILY_UNAVAILABLE, response.getMsg());
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] 国标级联 点播回复: {}", e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * 来自其他wvp的推流
     */
    private void otherWvpPushStream(RequestEvent evt, SIPRequest request, GbStream gbStream, StreamPush streamPushItem, ParentPlatform platform,
                                    CallIdHeader callIdHeader, MediaServerItem mediaServerItem,
                                    int port, Boolean tcpActive, boolean mediaTransmissionTCP,
                                    String channelId, String addressStr, String ssrc, String requesterId) {
        logger.info("[级联点播]直播流来自其他平台，发送redis消息");
        // 发送redis消息
        redisGbPlayMsgListener.sendMsg(streamPushItem.getServerId(), streamPushItem.getMediaServerId(),
                streamPushItem.getApp(), streamPushItem.getStream(), addressStr, port, ssrc, requesterId,
                channelId, mediaTransmissionTCP, platform.isRtcp(),null, responseSendItemMsg -> {
                    SendRtpItem sendRtpItem = responseSendItemMsg.getSendRtpItem();
                    if (sendRtpItem == null || responseSendItemMsg.getMediaServerItem() == null) {
                        logger.warn("服务器端口资源不足");
                        try {
                            responseAck(request, Response.BUSY_HERE);
                        } catch (SipException e) {
                            logger.error("未处理的异常 ", e);
                        } catch (InvalidArgumentException e) {
                            logger.error("未处理的异常 ", e);
                        } catch (ParseException e) {
                            logger.error("未处理的异常 ", e);
                        }
                        return;
                    }
                    // 收到sendItem
                    if (tcpActive != null) {
                        sendRtpItem.setTcpActive(tcpActive);
                    }
                    sendRtpItem.setPlayType(InviteStreamType.PUSH);
                    // 写入redis， 超时时回复
                    sendRtpItem.setStatus(1);
                    sendRtpItem.setCallId(callIdHeader.getCallId());

                    sendRtpItem.setFromTag(request.getFromTag());
                    SIPResponse response = sendStreamAck(responseSendItemMsg.getMediaServerItem(), request,sendRtpItem, platform, evt);
                    if (response != null) {
                        sendRtpItem.setToTag(response.getToTag());
                    }
                    redisCatchStorage.updateSendRTPSever(sendRtpItem);
                }, (wvpResult) -> {

                    // 错误
                    if (wvpResult.getCode() == RedisGbPlayMsgListener.ERROR_CODE_OFFLINE) {
                        // 离线
                        // 查询是否在本机上线了
                        StreamPush currentStreamPushItem = streamPushService.getPush(streamPushItem.getApp(), streamPushItem.getStream());
                        if (currentStreamPushItem.isPushIng()) {
                            // 在线状态
                            pushStream(evt, request, gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
                                    mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);

                        } else {
                            // 不在线 拉起
                            notifyStreamOnline(evt, request, gbStream, streamPushItem, platform, callIdHeader, mediaServerItem, port, tcpActive,
                                    mediaTransmissionTCP, channelId, addressStr, ssrc, requesterId);
                        }
                    }
                    try {
                        responseAck(request, Response.BUSY_HERE);
                    } catch (InvalidArgumentException | ParseException | SipException e) {
                        logger.error("[命令发送失败] 国标级联 点播回复 BUSY_HERE: {}", e.getMessage());
                    }
                });
    }

    public SIPResponse sendStreamAck(MediaServerItem mediaServerItem, SIPRequest request, SendRtpItem sendRtpItem, ParentPlatform platform, RequestEvent evt) {

        StringBuffer content = new StringBuffer(200);
        content.append("v=0\r\n");
        content.append("o=" + sendRtpItem.getChannelId() + " 0 0 IN IP4 " + mediaServerItem.getSdpIp() + "\r\n");
        content.append("s=Play\r\n");
        content.append("c=IN IP4 " + mediaServerItem.getSdpIp() + "\r\n");
        content.append("t=0 0\r\n");
        // 非严格模式端口不统一, 增加兼容性，修改为一个不为0的端口
        int localPort = sendRtpItem.getLocalPort();
        if(localPort == 0)
        {
            localPort = new Random().nextInt(65535) + 1;
        }
        content.append("m=video " + localPort + " RTP/AVP 96\r\n");
        content.append("a=sendonly\r\n");
        content.append("a=rtpmap:96 PS/90000\r\n");
        if (sendRtpItem.isTcp()) {
            content.append("a=connection:new\r\n");
            if (!sendRtpItem.isTcpActive()) {
                content.append("a=setup:active\r\n");
            } else {
                content.append("a=setup:passive\r\n");
            }
        }
        content.append("y=" + sendRtpItem.getSsrc() + "\r\n");
        content.append("f=\r\n");

        try {
            return responseSdpAck(request, content.toString(), platform);
        } catch (SipException e) {
            logger.error("未处理的异常 ", e);
        } catch (InvalidArgumentException e) {
            logger.error("未处理的异常 ", e);
        } catch (ParseException e) {
            logger.error("未处理的异常 ", e);
        }
        return null;
    }

    public void inviteFromDeviceHandle(SIPRequest request, String requesterId) {

        // 非上级平台请求，查询是否设备请求（通常为接收语音广播的设备）
        Device device = redisCatchStorage.getDevice(requesterId);
        if (device != null) {
            logger.info("收到设备" + requesterId + "的语音广播Invite请求");
            try {
                responseAck(request, Response.TRYING);
            } catch (SipException | InvalidArgumentException | ParseException e) {
                logger.error("[命令发送失败] invite BAD_REQUEST: {}", e.getMessage());
            }
            String contentString = new String(request.getRawContent());
            // jainSip不支持y=字段， 移除移除以解析。
            String ssrc = "0000000404";

            try {
                Gb28181Sdp gb28181Sdp = SipUtils.parseSDP(contentString);
                SessionDescription sdp = gb28181Sdp.getBaseSdb();
                //  获取支持的格式
                Vector mediaDescriptions = sdp.getMediaDescriptions(true);
                // 查看是否支持PS 负载96
                int port = -1;
                //boolean recvonly = false;
                boolean mediaTransmissionTCP = false;
                Boolean tcpActive = null;
                for (int i = 0; i < mediaDescriptions.size(); i++) {
                    MediaDescription mediaDescription = (MediaDescription) mediaDescriptions.get(i);
                    Media media = mediaDescription.getMedia();

                    Vector mediaFormats = media.getMediaFormats(false);
                    if (mediaFormats.contains("8")) {
                        port = media.getMediaPort();
                        String protocol = media.getProtocol();
                        // 区分TCP发流还是udp， 当前默认udp
                        if ("TCP/RTP/AVP".equals(protocol)) {
                            String setup = mediaDescription.getAttribute("setup");
                            if (setup != null) {
                                mediaTransmissionTCP = true;
                                if ("active".equals(setup)) {
                                    tcpActive = true;
                                } else if ("passive".equals(setup)) {
                                    tcpActive = false;
                                }
                            }
                        }
                        break;
                    }
                }
                if (port == -1) {
                    logger.info("不支持的媒体格式，返回415");
                    // 回复不支持的格式
                    try {
                        responseAck(request, Response.UNSUPPORTED_MEDIA_TYPE); // 不支持的格式，发415
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        logger.error("[命令发送失败] invite 不支持的媒体格式，返回415， {}", e.getMessage());
                    }
                    return;
                }
                String username = sdp.getOrigin().getUsername();
                String addressStr = sdp.getConnection().getAddress();
                logger.info("设备{}请求语音流，地址：{}:{}，ssrc：{}", username, addressStr, port, ssrc);
            } catch (SdpException e) {
                logger.error("[SDP解析异常]", e);
            }



        } else {
            logger.warn("来自无效设备/平台的请求");
            try {
                responseAck(request, Response.BAD_REQUEST);; // 不支持的格式，发415
            } catch (SipException | InvalidArgumentException | ParseException e) {
                logger.error("[命令发送失败] invite 来自无效设备/平台的请求， {}", e.getMessage());
            }
        }
    }
}
