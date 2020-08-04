package com.softwaremill.mqperf.mq

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import com.softwaremill.mqperf.config.TestConfig
import com.softwaremill.mqperf.mq.RMReceiver.mq
import com.softwaremill.mqperf.mq.RMSender.mq
import eventstore.akka.tcp.ConnectionActor
import eventstore.akka.{LiveProcessingStarted, Settings}
import eventstore.cluster.{ClusterSettings, GossipSeedsOrDns}
import eventstore._
import eventstore.akka.PersistentSubscriptionActor.ManualAck

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

class EventStoreMq(testConfig: TestConfig) extends Mq {

  private val StreamId = "mqperf-stream"
  private val GroupName = "mqperf-group"

  private val system = ActorSystem()
  private val firstHost = testConfig.brokerHosts.head
  private val settings = Settings(
    address = new InetSocketAddress(firstHost, 2112),
    cluster = Some(
      ClusterSettings(
        gossipSeedsOrDns = GossipSeedsOrDns(testConfig.brokerHosts.map(h => new InetSocketAddress(h, 2112)): _*)
      )
    )
  )

  private val connection = system.actorOf(ConnectionActor.props(settings))
  connection ! PersistentSubscription.Create(
    EventStream.Id(StreamId),
    GroupName,
    new PersistentSubscriptionSettings(
      readBatchSize = 1000,
      historyBufferSize = 1000,
      maxCheckPointCount = 10000,
      maxSubscriberCount = 25
    )
  )

  override type MsgId = UUID

  private lazy val receiveActor = system.actorOf(Props(new AddToBufferActor))
  private lazy val subscriptionActor = system.actorOf(
    PersistentSubscriptionActor.props(
      connection,
      receiveActor,
      EventStream.Id(StreamId),
      GroupName,
      None,
      settings,
      autoAck = false
    )
  )
  private val msgBuffer = new ConcurrentLinkedQueue[(UUID, String)]()

  private class AddToBufferActor extends Actor with ActorLogging {
    def receive: Receive = {
      case event: EventRecord =>
        msgBuffer.offer((event.data.eventId, event.data.data.value.utf8String))
      case LiveProcessingStarted =>
    }
  }

  override def createReceiver(): MqReceiver =
    new MqReceiver {
      subscriptionActor // force creation

      override def receive(maxMsgCount: Int): List[(UUID, String)] = {
        doReceive(Nil, waitForMsgs = 10, maxMsgCount)
      }

      @tailrec
      private def doReceive(acc: List[(UUID, String)], waitForMsgs: Int, count: Int): List[(UUID, String)] = {
        if (count == 0) {
          acc
        } else {
          val message = msgBuffer.poll()
          if (message == null && waitForMsgs > 0) {
            Thread.sleep(100L)
            doReceive(acc, waitForMsgs - 1, count)
          } else if (message == null) {
            acc
          } else {
            doReceive(message :: acc, 0, count - 1)
          }
        }
      }

      override def ack(ids: List[UUID]): Unit = {
        ids.foreach { i => subscriptionActor ! ManualAck(i) }
      }
    }

  private class WriteListener(p: Promise[Unit]) extends Actor with ActorLogging {
    def receive: Receive = {
      case WriteEventsCompleted(_, _) =>
        p.success(())
        context.stop(self)

      case Failure(e: EsException) =>
        p.failure(e)
        context.stop(self)
    }
  }

  override def createSender(): MqSender =
    new MqSender {
      override def send(msgs: List[String]): Unit = {
        val events = msgs.map(m => EventData("e", data = Content(m)))
        val p = Promise[Unit]()
        implicit val writeListener: ActorRef = system.actorOf(Props(new WriteListener(p)))
        connection ! WriteEvents(EventStream.Id(StreamId), events)
        Await.result(p.future, 10.seconds)
      }
    }

  override def close(): Unit = {
    system.terminate()
  }
}

object ESSender extends App {
  println("iteration 3")
  val mq = new EventStoreMq(
    TestConfig("", "", 0, 0, 0, 0, 0, 0, List("172.42.238.3", "172.42.238.4", "172.42.238.5"), "", null)
  )
  mq.createSender().send(List("a", "b", "c", "d"))
  println("SENT")
  mq.close()
}

object ESReceiver extends App {
  println("iteration 3")
  val mq = new EventStoreMq(
    TestConfig("", "", 0, 0, 0, 0, 0, 0, List("172.42.238.3", "172.42.238.4", "172.42.238.5"), "", null)
  )
  val rcv = mq.createReceiver()
  for (i <- 1 to 100) {
    val r = rcv.receive(10)
    println("GOT " + r)
    rcv.ack(r.map(_._1))
    Thread.sleep(1000)
  }
  mq.close()
}
