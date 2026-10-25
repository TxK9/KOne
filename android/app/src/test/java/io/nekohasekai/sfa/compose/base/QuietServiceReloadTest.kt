package io.nekohasekai.sfa.compose.base

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuietServiceReloadTest {
 @Test fun rapidChangesCoalesceAndNeverReloadConcurrently() = runBlocking {
  val work=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>()
  val running=java.util.concurrent.atomic.AtomicInteger();val count=java.util.concurrent.atomic.AtomicInteger();val max=java.util.concurrent.atomic.AtomicInteger()
  val queue=QuietServiceReload(work,{true},{
   max.accumulateAndGet(running.incrementAndGet(),::maxOf)
   if(count.incrementAndGet()==1){entered.complete(Unit);release.await()}else second.complete(Unit)
   running.decrementAndGet()
  },{throw it},20)
  try {
   repeat(10){queue.request()}
   withTimeout(3000){entered.await()}
   repeat(20){queue.request()}
   release.complete(Unit)
   withTimeout(3000){second.await()}
   delay(80)
   assertEquals(2,count.get());assertEquals(1,max.get())
  } finally {work.cancel()}
 }
 @Test fun disconnectedSettingsDoNotReloadAndFailureDoesNotStopLaterChanges() = runBlocking {
  val work=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  var started=false;var calls=0
  val failed=CompletableDeferred<Unit>();val recovered=CompletableDeferred<Unit>()
  val queue=QuietServiceReload(work,{started},{calls++;if(calls==1)error("synthetic failure") else recovered.complete(Unit)},{failed.complete(Unit)},10)
  try{
   queue.request();delay(80);assertEquals(0,calls)
   started=true;queue.request();withTimeout(3000){failed.await()}
   queue.request();withTimeout(3000){recovered.await()};assertEquals(2,calls)
  }finally{work.cancel()}
 }
}
