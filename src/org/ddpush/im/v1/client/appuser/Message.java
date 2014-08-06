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

import java.net.SocketAddress;
import java.nio.ByteBuffer;


public final class Message {
	
	public static int version = 1;
	public static final int SERVER_MESSAGE_MIN_LENGTH = 5;
	public static final int CLIENT_MESSAGE_MIN_LENGTH = 21;
	public static final int CMD_0x00 = 0;//心跳包
	public static final int CMD_0x10 = 16;//通用信息
	public static final int CMD_0x11 = 17;//分类信息
	public static final int CMD_0x20 = 32;//自定义信息
	
	protected SocketAddress address;
	protected byte[] data;
	
	public Message(SocketAddress address, byte[] data){
		this.address = address;
		this.data = data;
	}
	
	public int getContentLength(){
		return (int)ByteBuffer.wrap(data, SERVER_MESSAGE_MIN_LENGTH - 2, 2).getChar();
	}
	
	public int getCmd(){
		byte b = data[2];
		return b & 0xff;
	}
	
	public boolean checkFormat(){
		if(address == null || data == null || data.length < Message.SERVER_MESSAGE_MIN_LENGTH){
			return false;
		}
		int cmd = getCmd();
		if(cmd != CMD_0x00
				&& cmd != CMD_0x10
				&& cmd != CMD_0x11
				&& cmd != CMD_0x20){
			return false;
		}
		int dataLen = getContentLength();
		if(data.length != dataLen + SERVER_MESSAGE_MIN_LENGTH){
			return false;
		}
		if(cmd ==  CMD_0x10 && dataLen != 0){
			return false;
		}
		
		if(cmd ==  CMD_0x11 && dataLen != 8){
			return false;
		}
		
		if(cmd ==  CMD_0x20 && dataLen < 1){//must has content
			return false;
		}
		return true;
	}
	
	public void setData(byte[] data){
		this.data = data;
	}
	
	public byte[] getData(){
		return this.data;
	}
	
	public void setSocketAddress(SocketAddress address){
		this.address = address;
	}
	
	public SocketAddress getSocketAddress(){
		return this.address;
	}
	
	public static void setVersion(int v){
		if(v < 1 || v > 255){
			return;
		}
		version = v;
	}
	
	public static int getVersion(){
		return version;
	}

}
