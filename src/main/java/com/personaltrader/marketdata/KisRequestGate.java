package com.personaltrader.marketdata;
import java.util.concurrent.locks.*;
import java.util.function.Supplier;
/** One transport lane. Explicit manual requests take the next turn; collectors yield between HTTP reads. */
final class KisRequestGate {
 private final ReentrantLock lock=new ReentrantLock(true);private final Condition turn=lock.newCondition();
 private Thread owner;private int depth,priorityWaiting;private boolean ownerPriority;
 <T>T run(boolean priority,Supplier<T> action){acquire(priority);try{return action.get();}finally{release();}}
 private void acquire(boolean priority){lock.lock();try{if(owner==Thread.currentThread()){depth++;return;}if(priority)priorityWaiting++;
  try{while(owner!=null||!priority&&priorityWaiting>0)turn.awaitUninterruptibly();owner=Thread.currentThread();ownerPriority=priority;depth=1;}finally{if(priority)priorityWaiting--;}
 }finally{lock.unlock();}}
 private void release(){lock.lock();try{if(owner!=Thread.currentThread())throw new IllegalStateException();if(--depth==0){owner=null;turn.signalAll();}}finally{lock.unlock();}}
 void yieldToPriority(){lock.lock();try{if(owner!=Thread.currentThread()||ownerPriority||priorityWaiting==0)return;int saved=depth;owner=null;depth=0;turn.signalAll();while(owner!=null||priorityWaiting>0)turn.awaitUninterruptibly();owner=Thread.currentThread();depth=saved;}finally{lock.unlock();}}
}
