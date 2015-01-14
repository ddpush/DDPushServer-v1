package org.ddpush;

import org.ddpush.im.util.StringUtil;
import org.ddpush.im.v1.client.appserver.Pusher;

import java.util.concurrent.atomic.AtomicInteger;

public class BulkPushTest {

	public int threads = 500;
	public int threadPushTimes = 500;
	public final Object initSignal = new Object();
	public final Object startSignal = new Object();
	public Thread[] workers = null;;
	public AtomicInteger errCnt = null;
	public AtomicInteger pushCnt = null;

	public void init() throws Exception {
		System.out.println("threads: "+threads);
		System.out.println("thread push times: "+threadPushTimes);
		workers = new Thread[threads];
		errCnt = new AtomicInteger();
		pushCnt = new AtomicInteger();
		for(int i = 0; i < threads; i++){
			synchronized(initSignal){
				workers[i] = new Thread(new Runnable() {
					public void run(){
						Pusher pusher = null;
						try {
							boolean result =false;
							synchronized(startSignal){
								synchronized(initSignal){
									pusher = new Pusher("127.0.0.1", 9999, 5000);
									initSignal.notify();
								}
								startSignal.wait();
							}
							System.out.println("worker thread started: "+Thread.currentThread().getName());
							for (int i = 0; i < threadPushTimes; i++) {
//								 result = pusher.push0x20Message(StringUtil.hexStringToByteArray("2cb1abca847b4491bc2b206b592b64fd"),
//								 "cmd=ntfurl|title=通知标题|content=通知内容|tt=提示标题提示标题提示标题提示标题提示标题提示标题提示标题提示标题提示标题提示标题|url=/m/admin/eml/inbox/list".getBytes("UTF-8"));
//								 if(result == false)
//								 System.out.println("false:");
								result = pusher.push0x10Message(StringUtil.md5Byte(Thread.currentThread().getName()+"-"+i));
								if (result == false)System.out.println("false:"); else pushCnt.addAndGet(1);
								result = pusher.push0x11Message(StringUtil.md5Byte(Thread.currentThread().getName()+"-"+i),128);
								if (result == false)System.out.println("false:"); else pushCnt.addAndGet(1);
							}
						}catch(java.net.SocketTimeoutException te){
							errCnt.addAndGet(1);
						}catch (Exception e) {
							e.printStackTrace();
						} finally {
							try {
								if (pusher != null) {
									pusher.close();
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					}
				});
				workers[i].start();
				initSignal.wait();
			}
		}
	}

	public void start() throws Exception {
		synchronized(startSignal){
			startSignal.notifyAll();
		}
		for(int i = 0; i < workers.length; i++){
			workers[i].join();
		}

	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		try {
			BulkPushTest test = new BulkPushTest();
			try{
			int t = Integer.parseInt(args[0]);
			int cnt = Integer.parseInt(args[1]);
			test.threads = t;
			test.threadPushTimes = cnt;
			}catch(Exception e){
				System.out.println("args error");
			}
			System.out.println("waiting for init of "+test.threads+" threads ");
			test.init();
			System.out.println("init is done");
			long start = System.currentTimeMillis();
			System.out.println("start at: " + start);
			test.start();
			long used = System.currentTimeMillis() - start;
			System.out.println("used millis: " + (used));
			int pushTimes = test.threadPushTimes*test.threads*2;
			System.out.println("push times: "+pushTimes);
			System.out.println("push success times: "+test.pushCnt.get());
			System.out.println("read timeout threads: " + test.errCnt.get());
			System.out.println("push times per second: "+((test.pushCnt.get())/(((float)used)/1000)));
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
		}

	}

}
