package com.ruiyun.jvppeteer.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruiyun.jvppeteer.Constant;
import com.ruiyun.jvppeteer.events.browser.definition.BrowserEvent;
import com.ruiyun.jvppeteer.events.browser.definition.BrowserEventPublisher;
import com.ruiyun.jvppeteer.events.browser.definition.BrowserListener;
import com.ruiyun.jvppeteer.events.browser.definition.Events;
import com.ruiyun.jvppeteer.events.browser.impl.DefaultBrowserListener;
import com.ruiyun.jvppeteer.events.browser.impl.DefaultBrowserPublisher;
import com.ruiyun.jvppeteer.exception.ProtocolException;
import com.ruiyun.jvppeteer.exception.TimeOutException;
import com.ruiyun.jvppeteer.protocol.target.TargetInfo;
import com.ruiyun.jvppeteer.transport.message.SendMsg;
import com.ruiyun.jvppeteer.transport.websocket.CDPSession;
import com.ruiyun.jvppeteer.util.Factory;
import com.ruiyun.jvppeteer.util.Helper;
import com.ruiyun.jvppeteer.util.StringUtil;
import com.ruiyun.jvppeteer.util.ValidateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
/**
 * web socket client 浏览器级别的连接
 * @author fff
 *
 */
public class Connection implements Constant,Consumer<String>{
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Connection.class);
	
	/**websoket url */
	private String url;
	
	private ConnectionTransport transport;
	/**
	 * The unit is millisecond
	 */
	private int delay;
	
	private static final AtomicLong adder = new AtomicLong(0);
	
	private  Map<Long,SendMsg> callbacks = new ConcurrentHashMap<>();//并发
	
	private Map<String, CDPSession> sessions = new HashMap<>();



	private BlockingQueue responseQueue = new LinkedBlockingQueue();//接受消息的堵塞队列

	private int port;

	public Connection(String url,ConnectionTransport transport, int delay) {
		super();
		this.url = url;
		this.transport = transport;
		this.delay = delay;
		this.transport.addMessageConsumer(this);

		int start  = url.lastIndexOf(":");
		int end = url.indexOf("/",start);
		this.port = Integer.parseInt(url.substring(start + 1 ,end));
	}
	
	public Object send(String method,Map<String, Object> params,boolean isWait)  {
		SendMsg  message = new SendMsg();
		message.setMethod(method);
		message.setParams(params);
		try {
			long id = rawSend(message);
			if(id >= 0){
				callbacks.putIfAbsent(id, message);
				if(isWait){
					CountDownLatch latch = new CountDownLatch(1);
					message.setCountDownLatch(latch);
					boolean hasResult = message.waitForResult(DEFAULT_TIMEOUT,TimeUnit.MILLISECONDS);
					if(!hasResult){
						throw new TimeOutException("Wait result for "+DEFAULT_TIMEOUT+" MILLISECONDS with no response");
					}
				}
				return callbacks.remove(id).getResult();
			}
		} catch (InterruptedException e) {
			LOGGER.error("Waiting message is interrupted,will not recevie any message about on this send ");
		}
		return null;
	}
	
	public long rawSend(SendMsg  message)  {
		long id = adder.incrementAndGet();
		message.setId(id);
		try {
			String sendMsg = OBJECTMAPPER.writeValueAsString(message);
			LOGGER.info("SEND -> "+sendMsg);
			System.out.println("SEND -> "+sendMsg);
			transport.send(sendMsg);
			return id;
		}catch (JsonProcessingException e){
			LOGGER.error("parse message fail:",e);
		}
		return -1;
	}
	/**
	 * recevie message from browser by websocket
	 * @param message
	 */
	public void onMessage(String message) {
		if(delay >= 0) {
			try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				LOGGER.error("slowMo browser Fail:",e);
			}
		}
		
		LOGGER.info("<- RECV "+message);
		System.out.println("<- RECV "+message);
		try {
			if(StringUtil.isNotEmpty(message)) {
				JsonNode readTree = OBJECTMAPPER.readTree(message);
				JsonNode methodNode = readTree.get(RECV_MESSAGE_METHOD_PROPERTY);
				String method = null;
				if(methodNode != null){
					method = methodNode.asText();
				}
				if("Target.attachedToTarget".equals(method)) {//attached to target -> page attached to browser
					JsonNode paramsNode = readTree.get(RECV_MESSAGE_PARAMS_PROPERTY);
					JsonNode sessionId = paramsNode.get(RECV_MESSAGE_SESSION_ID_PROPERTY);
					JsonNode typeNode = paramsNode.get(RECV_MESSAGE_TARGETINFO_PROPERTY).get(RECV_MESSAGE_TYPE_PROPERTY);
					CDPSession cdpSession = new CDPSession(this,typeNode.asText(), sessionId.asText());
					sessions.put(sessionId.asText(), cdpSession);
				}else if("Target.detachedFromTarget".equals(method)) {//页面与浏览器脱离关系
					JsonNode paramsNode = readTree.get(RECV_MESSAGE_PARAMS_PROPERTY);
					JsonNode sessionId = paramsNode.get(RECV_MESSAGE_SESSION_ID_PROPERTY);
					String sessionIdString = sessionId.asText();
					CDPSession cdpSession = sessions.get(sessionIdString);
					if(cdpSession != null){
						cdpSession.onClosed();
						sessions.remove(sessionIdString);
					}
				}
				JsonNode objectSessionId = readTree.get(RECV_MESSAGE_SESSION_ID_PROPERTY);
				JsonNode objectId = readTree.get(RECV_MESSAGE_ID_PROPERTY);
				if(objectSessionId != null) {//cdpsession消息，当然cdpsession来处理
					String objectSessionIdString = objectSessionId.asText();
					CDPSession cdpSession = this.sessions.get(objectSessionIdString);
					if(cdpSession != null) {
						cdpSession.onMessage(readTree);
					}
				}else if(objectId != null) {//long类型的id,说明属于这次发送消息后接受的回应
					SendMsg sendMsg = this.callbacks.get(objectId.asLong());
					JsonNode error = readTree.get(RECV_MESSAGE_ERROR_PROPERTY);
					if(error != null){
						if(sendMsg.getCountDownLatch() != null && sendMsg.getCountDownLatch().getCount() >0){
							sendMsg.getCountDownLatch().countDown();
						}
						throw new ProtocolException(Helper.createProtocolError(readTree));
					}else{
						JsonNode result = readTree.get(RECV_MESSAGE_RESULT_PROPERTY);
						sendMsg.setResult(result);
						if(sendMsg.getCountDownLatch() != null && sendMsg.getCountDownLatch().getCount() >0){
							sendMsg.getCountDownLatch().countDown();
						}
					}
				}else{//是我们监听的事件，把它事件

					JsonNode paramsNode = readTree.get(RECV_MESSAGE_PARAMS_PROPERTY);
					publishEvent(method,paramsNode);
				}
			}
		} catch (Exception e) {
			LOGGER.error("parse recv message fail:",e);
		}
		
		
	}

	/**
	 * publish event
	 * @param method
	 * @param params
	 */

	public void publishEvent(String method, JsonNode params) throws ExecutionException {
		Factory.get(DefaultBrowserPublisher.class.getSimpleName()+this.port,DefaultBrowserPublisher.class).publishEvent2(method,params);
	}

	public CDPSession createSession(TargetInfo targetInfo){
		Map<String, Object> params = new HashMap<>();
		params.put("targetId",targetInfo.getTargetId());
		params.put("flatten",true);
		this.send("Target.attachToTarget", params,false);

		//TODO 监听
		return this.sessions.get("");
	}



	public String url() {
		 return this.url;
	}

	public String getUrl() {
		return url;
	}

	public CDPSession session(String sessionId) {
	    return sessions.get(sessionId);
	 }

	@Override
	public void accept(String t) {
		onMessage(t);
	}


	public int getPort() {
		return port;
	}


}

