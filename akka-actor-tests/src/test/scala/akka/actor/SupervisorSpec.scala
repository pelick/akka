/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import org.scalatest.BeforeAndAfterEach
import scala.concurrent.duration._
import akka.{ Die, Ping }
import akka.testkit.TestEvent._
import akka.testkit._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import akka.pattern.ask

object SupervisorSpec {
  val Timeout = 5.seconds

  case object DieReply

  // =====================================================
  // Message logs
  // =====================================================

  val PingMessage = "ping"
  val PongMessage = "pong"
  val ExceptionMessage = "Expected exception; to test fault-tolerance"

  // =====================================================
  // Actors
  // =====================================================

  class PingPongActor(sendTo: ActorRef) extends Actor {
    def receive = {
      case Ping ⇒
        sendTo ! PingMessage
        if (sender() != sendTo)
          sender() ! PongMessage
      case Die ⇒
        throw new RuntimeException(ExceptionMessage)
      case DieReply ⇒
        val e = new RuntimeException(ExceptionMessage)
        sender() ! Status.Failure(e)
        throw e
    }

    override def postRestart(reason: Throwable) {
      sendTo ! reason.getMessage
    }
  }

  class Master(sendTo: ActorRef) extends Actor {
    val temp = context.watch(context.actorOf(Props(new PingPongActor(sendTo))))

    var s: ActorRef = _

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 0)(List(classOf[Exception]))

    def receive = {
      case Die                ⇒ temp forward Die
      case Terminated(`temp`) ⇒ sendTo ! "terminated"
      case Status.Failure(_)  ⇒ /*Ignore*/
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorSpec extends AkkaSpec("akka.actor.serialize-messages = off") with BeforeAndAfterEach with ImplicitSender with DefaultTimeout {

  import SupervisorSpec._

  val DilatedTimeout = Timeout.dilated

  // =====================================================
  // Creating actors and supervisors
  // =====================================================

  private def child(supervisor: ActorRef, props: Props): ActorRef = Await.result((supervisor ? props).mapTo[ActorRef], timeout.duration)

  def temporaryActorAllForOne = {
    val supervisor = system.actorOf(Props(new Supervisor(AllForOneStrategy(maxNrOfRetries = 0)(List(classOf[Exception])))))
    val temporaryActor = child(supervisor, Props(new PingPongActor(testActor)))

    (temporaryActor, supervisor)
  }

  def singleActorAllForOne = {
    val supervisor = system.actorOf(Props(new Supervisor(
      AllForOneStrategy(maxNrOfRetries = 3, withinTimeRange = DilatedTimeout)(List(classOf[Exception])))))
    val pingpong = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong, supervisor)
  }

  def singleActorOneForOne = {
    val supervisor = system.actorOf(Props(new Supervisor(
      OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = DilatedTimeout)(List(classOf[Exception])))))
    val pingpong = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong, supervisor)
  }

  def multipleActorsAllForOne = {
    val supervisor = system.actorOf(Props(new Supervisor(
      AllForOneStrategy(maxNrOfRetries = 3, withinTimeRange = DilatedTimeout)(List(classOf[Exception])))))
    val pingpong1, pingpong2, pingpong3 = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def multipleActorsOneForOne = {
    val supervisor = system.actorOf(Props(new Supervisor(
      OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = DilatedTimeout)(List(classOf[Exception])))))
    val pingpong1, pingpong2, pingpong3 = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def nestedSupervisorsAllForOne = {
    val topSupervisor = system.actorOf(Props(new Supervisor(
      AllForOneStrategy(maxNrOfRetries = 3, withinTimeRange = DilatedTimeout)(List(classOf[Exception])))))
    val pingpong1 = child(topSupervisor, Props(new PingPongActor(testActor)))

    val middleSupervisor = child(topSupervisor, Props(new Supervisor(
      AllForOneStrategy(maxNrOfRetries = 3, withinTimeRange = DilatedTimeout)(Nil))))
    val pingpong2, pingpong3 = child(middleSupervisor, Props(new PingPongActor(testActor)))

    (pingpong1, pingpong2, pingpong3, topSupervisor)
  }

  override def atStartup() {
    system.eventStream.publish(Mute(EventFilter[RuntimeException](ExceptionMessage)))
  }

  override def beforeEach() = {

  }

  def ping(pingPongActor: ActorRef) = {
    Await.result(pingPongActor.?(Ping)(DilatedTimeout), DilatedTimeout) should be(PongMessage)
    expectMsg(Timeout, PingMessage)
  }

  def kill(pingPongActor: ActorRef) = {
    val result = (pingPongActor.?(DieReply)(DilatedTimeout))
    expectMsg(Timeout, ExceptionMessage)
    intercept[RuntimeException] { Await.result(result, DilatedTimeout) }
  }

  "A supervisor" must {

    "not restart child more times than permitted" in {
      val master = system.actorOf(Props(new Master(testActor)))

      master ! Die
      expectMsg(3 seconds, "terminated")
      expectNoMsg(1 second)
    }

    "restart properly when same instance is returned" in {
      val restarts = 3 //max number of restarts
      lazy val childInstance = new Actor {
        var preRestarts = 0
        var postRestarts = 0
        var preStarts = 0
        var postStops = 0
        override def preRestart(reason: Throwable, message: Option[Any]) { preRestarts += 1; testActor ! ("preRestart" + preRestarts) }
        override def postRestart(reason: Throwable) { postRestarts += 1; testActor ! ("postRestart" + postRestarts) }
        override def preStart() { preStarts += 1; testActor ! ("preStart" + preStarts) }
        override def postStop() { postStops += 1; testActor ! ("postStop" + postStops) }
        def receive = {
          case "crash" ⇒ { testActor ! "crashed"; throw new RuntimeException("Expected") }
          case "ping"  ⇒ sender() ! "pong"
        }
      }
      val master = system.actorOf(Props(new Actor {
        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = restarts)(List(classOf[Exception]))
        val child = context.actorOf(Props(childInstance))
        def receive = {
          case msg ⇒ child forward msg
        }
      }))

      expectMsg("preStart1")

      master ! "ping"
      expectMsg("pong")

      filterEvents(EventFilter[RuntimeException]("Expected", occurrences = restarts + 1)) {
        (1 to restarts) foreach {
          i ⇒
            master ! "crash"
            expectMsg("crashed")

            expectMsg("preRestart" + i)
            expectMsg("postRestart" + i)

            master ! "ping"
            expectMsg("pong")
        }
        master ! "crash"
        expectMsg("crashed")
        expectMsg("postStop1")
      }

      expectNoMsg(1 second)
    }

    "not restart temporary actor" in {
      val (temporaryActor, _) = temporaryActorAllForOne

      intercept[RuntimeException] { Await.result(temporaryActor.?(DieReply)(DilatedTimeout), DilatedTimeout) }

      expectNoMsg(1 second)
    }

    "start server for nested supervisor hierarchy" in {
      val (actor1, _, _, _) = nestedSupervisorsAllForOne
      ping(actor1)
      expectNoMsg(1 second)
    }

    "kill single actor OneForOne" in {
      val (actor, _) = singleActorOneForOne
      kill(actor)
    }

    "call-kill-call single actor OneForOne" in {
      val (actor, supervisor) = singleActorOneForOne
      ping(actor)
      kill(actor)
      ping(actor)
    }

    "kill single actor AllForOne" in {
      val (actor, supervisor) = singleActorAllForOne
      kill(actor)
    }

    "call-kill-call single actor AllForOne" in {
      val (actor, supervisor) = singleActorAllForOne
      ping(actor)
      kill(actor)
      ping(actor)
    }

    "kill multiple actors OneForOne 1" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsOneForOne
      kill(actor1)
    }

    "kill multiple actors OneForOne 2" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsOneForOne
      kill(actor3)
    }

    "call-kill-call multiple actors OneForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsOneForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "kill multiple actors AllForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsAllForOne

      kill(actor2)

      // and two more exception messages
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, ExceptionMessage)
    }

    "call-kill-call multiple actors AllForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsAllForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      // and two more exception messages
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, ExceptionMessage)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "one-way kill single actor OneForOne" in {
      val (actor, _) = singleActorOneForOne

      actor ! Die
      expectMsg(Timeout, ExceptionMessage)
    }

    "one-way call-kill-call single actor OneForOne" in {
      val (actor, _) = singleActorOneForOne

      actor ! Ping
      actor ! Die
      actor ! Ping

      expectMsg(Timeout, PingMessage)
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, PingMessage)
    }

    "restart killed actors in nested superviser hierarchy" in {
      val (actor1, actor2, actor3, _) = nestedSupervisorsAllForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      // and two more exception messages
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, ExceptionMessage)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "attempt restart when exception during restart" in {
      val inits = new AtomicInteger(0)
      val supervisor = system.actorOf(Props(new Supervisor(
        OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 10 seconds)(classOf[Exception] :: Nil))))

      val dyingProps = Props(new Actor {
        val init = inits.getAndIncrement()
        if (init % 3 == 1) throw new IllegalStateException("Don't wanna!")

        override def preRestart(cause: Throwable, msg: Option[Any]) {
          if (init % 3 == 0) throw new IllegalStateException("Don't wanna!")
        }

        def receive = {
          case Ping ⇒ sender() ! PongMessage
          case DieReply ⇒
            val e = new RuntimeException("Expected")
            sender() ! Status.Failure(e)
            throw e
        }
      })
      supervisor ! dyingProps
      val dyingActor = expectMsgType[ActorRef]

      filterEvents(
        EventFilter[RuntimeException]("Expected", occurrences = 1),
        EventFilter[PreRestartException]("Don't wanna!", occurrences = 1),
        EventFilter[PostRestartException]("Don't wanna!", occurrences = 1)) {
          intercept[RuntimeException] {
            Await.result(dyingActor.?(DieReply)(DilatedTimeout), DilatedTimeout)
          }
        }

      dyingActor ! Ping
      expectMsg(PongMessage)

      inits.get should be(3)

      system.stop(supervisor)
    }

    "not lose system messages when a NonFatal exception occurs when processing a system message" in {
      val parent = system.actorOf(Props(new Actor {
        override val supervisorStrategy = OneForOneStrategy()({
          case e: IllegalStateException if e.getMessage == "OHNOES" ⇒ throw e
          case _ ⇒ SupervisorStrategy.Restart
        })
        val child = context.watch(context.actorOf(Props(new Actor {
          override def postRestart(reason: Throwable): Unit = testActor ! "child restarted"
          def receive = {
            case l: TestLatch ⇒ { Await.ready(l, 5 seconds); throw new IllegalStateException("OHNOES") }
            case "test"       ⇒ sender() ! "child green"
          }
        }), "child"))

        override def postRestart(reason: Throwable): Unit = testActor ! "parent restarted"

        // Overriding to disable auto-unwatch
        override def preRestart(reason: Throwable, msg: Option[Any]): Unit = {
          context.children foreach context.stop
          postStop()
        }

        def receive = {
          case Terminated(a) if a.path == child.path ⇒ testActor ! "child terminated"
          case l: TestLatch                          ⇒ child ! l
          case "test"                                ⇒ sender() ! "green"
          case "testchild"                           ⇒ child forward "test"
          case "testchildAndAck"                     ⇒ child forward "test"; sender() ! "ack"
        }
      }))

      val latch = TestLatch()
      parent ! latch
      parent ! "testchildAndAck"
      expectMsg("ack")
      filterEvents(
        EventFilter[IllegalStateException]("OHNOES", occurrences = 1),
        EventFilter.warning(pattern = "dead.*test", occurrences = 1)) {
          latch.countDown()
        }
      expectMsg("parent restarted")
      expectMsg("child terminated")
      parent ! "test"
      expectMsg("green")
      parent ! "testchild"
      expectMsg("child green")
    }
  }
}
