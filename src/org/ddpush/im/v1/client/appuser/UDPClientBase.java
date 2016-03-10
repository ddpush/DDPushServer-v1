/*
 *Copyright 2014 DDPush
 *Author: AndyKwok(in English) GuoZhengzhu(in Chinese)
 *Email: ddpush@126.com
 *

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

*/
package org.ddpush.im.v1.client.appuser;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public abstract class UDPClientBase implements Runnable {
	
	protected DatagramSocket ds;
	protected long lastSent = 0;
	protected long lastReceived = 0;
	protected int remotePort = 9966;
	protected int appid = 1;
	protected byte[] uuid;
	protected String remoteAddress = null;
	protected ConcurrentLinkedQueue<Message> mq = new ConcurrentLinkedQueue<Message>();
	
	protected AtomicLong queueIn = new AtomicLong(0);
	protected AtomicLong queueOut = new AtomicLong(0);

	protected int bufferSize = 1024;
	protected int heartbeatInterval = 50;
	
	protected byte[] bufferArray;
	protected ByteBuffer buffer;
	protected boolean needReset = true;
	
	protected boolean started = false;
	protected boolean stoped = false;
	
	protected Thread receiverT;
	protected Worker worker;
	protected Thread workerT;
	
	private long sentPackets;
	private long receivedPackets;
	
	public UDPClientBase(byte[] uuid, int appid, String serverAddr, int serverPort) throws Exception{
		if(uuid == null || uuid.length != 16){
			throw new java.lang.IllegalArgumentException("uuid byte array must be not null and length of 16 bytes");
		}
		if(appid < 1 || appid > 255){
			throw new java.lang.IllegalArgumentException("appid must be from 1 to 255");
		}
		if(serverAddr == null || serverAddr.trim().length() == 0){
			throw new java.lang.IllegalArgumentException("server address illegal: "+serverAddr);
		}

		this.uuid = uuid;
		this.appid = appid;
		this.remoteAddress = serverAddr;
		this.remotePort = serverPort;
	}
	
	protected boolean enqueue(Message message){
		boolean result = mq.add(message);
		if(result == true){
			queueIn.addAndGet(1);
		}
		return result;
	}
	
	protected Message dequeue(){
		Message m = mq.poll();
		if(m != null){
			queueOut.addAndGet(1);
		}
		return m;
	}
	
	private synchronized void init(){
		bufferArray = new byte[bufferSize];
		buffer = ByteBuffer.wrap(bufferArray);
	}
	
	protected synchronized void reset() throws Exception{
		if(needReset == false){
			return;
		}

		if(ds != null){
			try{ds.close();}catch(Exception e){}
		}
		if(hasNetworkConnection() == true){
			ds = new DatagramSocket();
			ds.connect(new InetSocketAddress(remoteAddress,remotePort));
			needReset = false;
		}else{
			try{Thread.sleep(1000);}catch(Exception e){}
		}
	}
	
	public synchronized void start() throws Exception{
		if(this.started == true){
			return;
		}
		this.init();
		
		receiverT = new Thread(this,"udp-client-receiver");
		receiverT.setDaemon(true);
		synchronized(receiverT){
			receiverT.start();
			receiverT.wait();
		}
		
		worker = new Worker();
		workerT = new Thread(worker,"udp-client-worker");
		workerT.setDaemon(true);
		synchronized(workerT){
			workerT.start();
			workerT.wait();
		}
		
		this.started = true;
	}
	
	public void stop() throws Exception{
		stoped = true;
		if(ds != null){
			try{ds.close();}catch(Exception e){}
			ds = null;
		}
		if(receiverT != null){
			try{receiverT.interrupt();}catch(Exception e){}
		}

		if(workerT != null){
			try{workerT.interrupt();}catch(Exception e){}
		}
	}
	
	public void run(){
		
		synchronized(receiverT){
			receiverT.notifyAll();
		}
		
		while(stoped == false){
			try{
				if(hasNetworkConnection() == false){
					try{
						trySystemSleep();
						Thread.sleep(1000);
					}catch(Exception e){}
					continue;
				}
				reset();
				heartbeat();
				receiveData();
			}catch(java.net.SocketTimeoutException e){
				
			}catch(Exception e){
				e.printStackTrace();
				this.needReset = true;
			}catch(Throwable t){
				t.printStackTrace();
				this.needReset = true;
			}finally{
				if(needReset == true){
					try{
						trySystemSleep();
						Thread.sleep(1000);
					}catch(Exception e){}
				}
				if(mq.isEmpty() == true || hasNetworkConnection() == false){
					try{
						trySystemSleep();
						Thread.sleep(1000);
					}catch(Exception e){}
				}
			}
		}
		if(ds != null){
			try{ds.close();}catch(Exception e){}
			ds = null;
		}
	}
	private void heartbeat() throws Exception{
		if(System.currentTimeMillis() - lastSent < heartbeatInterval * 1000){
			return;
		}
		byte[] buffer = new byte[Message.CLIENT_MESSAGE_MIN_LENGTH];
		ByteBuffer.wrap(buffer).put((byte)Message.version).put((byte)appid).put((byte)Message.CMD_0x00).put(uuid).putChar((char)0);
		send(buffer);
	}
	
	private void receiveData() throws Exception{
		DatagramPacket dp = new DatagramPacket(bufferArray, bufferArray.length);
		ds.setSoTimeout(5*1000);
		ds.receive(dp);
		if(dp.getLength() <= 0 || dp.getData() == null || dp.getData().length == 0){
			return;
		}
		byte[] data = new byte[dp.getLength()];
		
		System.arraycopy(dp.getData(), 0, data, 0, dp.getLength());
		Message m = new Message(dp.getSocketAddress(), data);
		if(m.checkFormat() == false){
			return;
		}
		this.receivedPackets++;
		this.lastReceived = System.currentTimeMillis();
		this.ackServer(m);
		if(m.getCmd() == Message.CMD_0x00){
			return;
		}
		this.enqueue(m);
		worker.wakeup();
	}
	
	private void ackServer(Message m) throws Exception{
		if(m.getCmd() == Message.CMD_0x10){
			byte[] buffer = new byte[Message.CLIENT_MESSAGE_MIN_LENGTH];
			ByteBuffer.wrap(buffer).put((byte)Message.version).put((byte)appid).put((byte)Message.CMD_0x10).put(uuid).putChar((char)0);
			send(buffer);
		}
		if(m.getCmd() == Message.CMD_0x11){
			byte[] buffer = new byte[Message.CLIENT_MESSAGE_MIN_LENGTH + 8];
			byte[] data = m.getData();
			ByteBuffer.wrap(buffer).put((byte)Message.version).put((byte)appid).put((byte)Message.CMD_0x11).put(uuid).putChar((char)8).put(data, Message.SERVER_MESSAGE_MIN_LENGTH, 8);
			send(buffer);
		}
		if(m.getCmd() == Message.CMD_0x20){
			byte[] buffer = new byte[Message.CLIENT_MESSAGE_MIN_LENGTH];
			ByteBuffer.wrap(buffer).put((byte)Message.version).put((byte)appid).put((byte)Message.CMD_0x20).put(uuid).putChar((char)0);
			send(buffer);
		}
	}
	
	private void send(byte[] data) throws Exception{
		if(data == null){
			return;
		}
		if(ds == null){
			return;
		}
		DatagramPacket dp = new DatagramPacket(data,data.length);
		dp.setSocketAddress(ds.getRemoteSocketAddress());
		ds.send(dp);
		lastSent = System.currentTimeMillis();
		this.sentPackets++;
	}
	
	public long getSentPackets(){
		return this.sentPackets;
	}
	
	public long getReceivedPackets(){
		return this.receivedPackets;
	}
	
	public void setServerPort(int port){
		this.remotePort = port;
	}
	
	public int getServerPort(){
		return this.remotePort;
	}
	
	public void setServerAddress(String addr){
		this.remoteAddress = addr;
	}
	
	public String getServerAddress(){
		return this.remoteAddress;
	}
	
	public void setBufferSize(int bytes){
		this.bufferSize = bytes;
	}
	
	public int getBufferSize(){
		return this.bufferSize;
	}
	
	public long getLastHeartbeatTime(){
		return lastSent;
	}
	
	public long getLastReceivedTime(){
		return lastReceived;
	}
	
	/*
	 * send heart beat every given seconds
	 */
	public void setHeartbeatInterval(int second){
		if(second <= 0){
			return;
		}
		this.heartbeatInterval = second;
	}
	
	public int getHeartbeatInterval(){
		return this.heartbeatInterval;
	}
	
	public abstract boolean hasNetworkConnection();
	public abstract void trySystemSleep();
	public abstract void onPushMessage(Message message);
	
	class Worker implements Runnable{
		public void run(){
			synchronized(workerT){
				workerT.notifyAll();
			}
			while(stoped == false){
				try{
					handleEvent();
				}catch(Exception e){
					e.printStackTrace();
				}finally{
					waitMsg();
				}
			}
		}
		
		private void waitMsg(){
			synchronized(this){
				try{
					this.wait(1000);
				}catch(java.lang.InterruptedException e){
					
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		}
		
		private void wakeup(){
			synchronized(this){
				this.notifyAll();
			}
		}
		
		private void handleEvent() throws Exception{
			Message m = null;
			while(true){
				m = dequeue();
				if(m == null){
					return;
				}
				if(m.checkFormat() == false){
					continue;
				}

				//real work here
				onPushMessage(m);
			}
			//finish work here, such as release wake lock
		}
		
	}
}
