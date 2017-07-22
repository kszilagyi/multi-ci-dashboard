package com.kristofszilagyi

import com.kristofszilagyi.shared.{BuildStatus, FetchResult, Wart}
import japgolly.scalajs.react.test.{ReactTestExt_MountedId, ReactTestUtils}
import utest._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers.SetIntervalHandle
import com.kristofszilagyi.shared.SameThreadExecutionContext._

final class MockTimers extends JsTimers {
  private val counter = Iterator.from(0)

  @SuppressWarnings(Array(Wart.Var))
  private var actions: Map[Int, () => Unit] = Map.empty

  @SuppressWarnings(Array(Wart.AsInstanceOf))
  def setInterval(interval: FiniteDuration, body: => Unit): SetIntervalHandle = {
    val id = counter.next()
    actions = actions + (id -> (() => body))
    id.asInstanceOf[SetIntervalHandle]
  }

  @SuppressWarnings(Array(Wart.AsInstanceOf))
  def clearInterval(handle: SetIntervalHandle): Unit = {
    val id = handle.asInstanceOf[Int]
    actions = actions - id
  }

  def executeAll(): Unit = {
    actions.values.foreach(body => body())
  }
}

final class MockAutowire extends MockableAutowire {
  def dataFeed(): Future[FetchResult] = {
    Future.successful(FetchResult(Right(Seq(Right(BuildStatus.Building)))))
  }
}

object TimerTest extends TestSuite{
  val mockTimers = new MockTimers()
  val TestTimer = Test.timer(mockTimers, new MockAutowire())

  def tests = TestSuite {
    'HelloWorld {
      ReactTestUtils.withRenderedIntoBody(TestTimer()) { testTimer =>
        println(testTimer.getDOMNode.textContent)
        mockTimers.executeAll()
        println(testTimer.getDOMNode.textContent)
      }
    }
  }
}
