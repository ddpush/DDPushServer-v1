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
package org.ddpush.im.v1.node.pushlistener;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.ddpush.im.util.PropertyUtil;
import org.ddpush.im.v1.node.ClientStatMachine;
import org.ddpush.im.v1.node.Constant;
import org.ddpush.im.v1.node.NodeStatus;
import org.ddpush.im.v1.node.PushMessage;

public class PushTask implements Runnable {
	
	private NIOPushListener listener;
	private SocketChannel channel;
	private SelectionKey key;
	private long lastActive;
	private boolean isCancel = false;
	
	private boolean writePending = false;
	private int maxContentLength;
	private byte[] bufferArray;
	private ByteBuffer buffer;
	
	public PushTask(NIOPushListener listener, SocketChannel channel){
		this.listener = listener;
		this.channel = channel;
		maxContentLength = PropertyUtil.getPropertyInt("PUSH_MSG_MAX_CONTENT_LEN");
		bufferArray = new byte[Constant.PUSH_MSG_HEADER_LEN+maxContentLength];
		buffer = ByteBuffer.wrap(bufferArray);
		buffer.limit(Constant.PUSH_MSG_HEADER_LEN);
		lastActive = System.currentTimeMillis();
	}
	
	public void setKey(SelectionKey key){
		this.key = key;
	}
	
	private void cancelKey(final SelectionKey key) {

        Runnable r = new Runnable() {
            public void run() {
            	listener.cancelKey(key);
            }
        };
        listener.addEvent(r);
    }
	
	private void registerForWrite(final SelectionKey key, final boolean needWrite) {
		if(key == null || key.isValid() == false){
			return;
		}
		
		if(needWrite == true){
			if((key.interestOps() & SelectionKey.OP_WRITE) > 0){
				return;
			}
		}else{
			if((key.interestOps() & SelectionKey.OP_WRITE) == 0){
				return;
			}
		}
		
		Runnable r = new Runnable() {
            public void run() {
            	if(key == null || !key.isValid()){
            		return;
            	}
            	key.selector().wakeup();
            	if(needWrite == true){
            		key.interestOps(key.interestOps()  & (~SelectionKey.OP_READ) | SelectionKey.OP_WRITE);
            	}else{
            		key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE) | SelectionKey.OP_READ);
            	}
            }
        };
        listener.addEvent(r);
        try{
        	key.selector().wakeup();
        }catch(Exception e){
        	e.printStackTrace();
        }
	}

	@Override
	public synchronized void run() {
		if(listener == null || channel == null){
			return;
		}
		
		if(key == null){
			return;
		}
		if(isCancel == true){
			return;
		}
		try{
			if(writePending == false){
				
				if(key.isReadable()){
					//read pkg
					readReq();
				}else{
					// do nothing
				}
			}else{//has package
				
				// try send pkg and place hasPkg=false
				//
				//register write ops if not enough buffer
				//if(key.isWritable()){
					writeRes();
				//}
			}
		}catch(Exception e){
			cancelKey(key);
			isCancel = true;
		}catch(Throwable t){
			cancelKey(key);
			isCancel = true;
		}
		
		key = null;

	}
	
	private void readReq() throws Exception{
		if(this.writePending == true){
			return;
		}
		
		if(channel.read(buffer) < 0){
			throw new Exception("end of stream");
		}
		if(this.calcWritePending() == false){
			return;
		}else{
			byte res = 0;
			try{
				processReq();
			}catch(Exception e){
				res = 1;
			}
			catch(Throwable t){
				res = -1;
			}
			
			buffer.clear();
			buffer.limit(1);
			buffer.put(res);
			buffer.flip();
			
			registerForWrite(key, true);
			
		}
			

		lastActive = System.currentTimeMillis();
	}
	
	private void writeRes() throws Exception{
		if(buffer.hasRemaining()){
			channel.write(buffer);
		}else{
			buffer.clear();
			buffer.limit(Constant.PUSH_MSG_HEADER_LEN);
			this.writePending = false;
			registerForWrite(key, false);
		}
		lastActive = System.currentTimeMillis();
	}
	
	public long getLastActive(){
		return lastActive;
	}
	
	public boolean isWritePending(){
		return writePending;
	}
	
	private synchronized boolean calcWritePending() throws Exception{
		if(this.writePending == false){
			if(buffer.position() < Constant.PUSH_MSG_HEADER_LEN){
				this.writePending = false;
			}else{
				int bodyLen = (int)ByteBuffer.wrap(bufferArray, Constant.PUSH_MSG_HEADER_LEN - 2, 2).getChar();
				if(bodyLen > maxContentLength){
					throw new java.lang.IllegalArgumentException("content length "+bodyLen+" larger than max "+maxContentLength);
				}
				if(bodyLen == 0){
					this.writePending = true;
				}else{
					if(buffer.limit() != Constant.PUSH_MSG_HEADER_LEN + bodyLen){
						buffer.limit(Constant.PUSH_MSG_HEADER_LEN + bodyLen);
					}else{
						if(buffer.position() == Constant.PUSH_MSG_HEADER_LEN + bodyLen){
							this.writePending = true;
						}
					}
				}
			}
		}else{//this.writePending == true
			if(buffer.hasRemaining()){
				this.writePending = true;
			}else{
				this.writePending = false;
			}
		}

		return this.writePending;
	}
	
	private void processReq() throws Exception{
		//check and put data into nodeStat
		buffer.flip();
		byte[] data = new byte[buffer.limit()];
		System.arraycopy(bufferArray, 0, data, 0, buffer.limit());
		buffer.clear();
		//this.writePending = false;//important
		PushMessage pm = new PushMessage(data);
		NodeStatus nodeStat = NodeStatus.getInstance();
		String uuid = pm.getUuidHexString(); 
		ClientStatMachine csm = nodeStat.getClientStat(uuid);
		if(csm == null){//
			csm = ClientStatMachine.newByPushReq(pm);
			if(csm == null){
				throw new Exception("can not new state machine");
			}
			nodeStat.putClientStat(uuid, csm);
		}else{
			try{csm.onPushMessage(pm);}catch(Exception e){};
		}

	}

}
