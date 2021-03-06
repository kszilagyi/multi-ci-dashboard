package com.kristofszilagyi.pipelineboard

import java.time.{Instant, ZoneId}

import com.kristofszilagyi.pipelineboard.Canvas.queryJobWindowWidth
import com.kristofszilagyi.pipelineboard.RenderUtils._
import com.kristofszilagyi.pipelineboard.shared.InstantOps._
import com.kristofszilagyi.pipelineboard.shared.MyStyles.{labelEnd, rightMargin}
import com.kristofszilagyi.pipelineboard.shared.Wart._
import com.kristofszilagyi.pipelineboard.shared._
import japgolly.scalajs.react.extra.{EventListener, OnUnmount}
import japgolly.scalajs.react.raw.{SyntheticMouseEvent, SyntheticWheelEvent}
import japgolly.scalajs.react.vdom._
import japgolly.scalajs.react.vdom.svg_<^.{<, _}
import japgolly.scalajs.react.{BackendScope, Callback, CallbackTo, ScalaComponent}
import org.scalajs.dom.Element
import org.scalajs.dom.raw.SVGElement
import slogging.LazyLogging
import TypeSafeAttributes._
import com.kristofszilagyi.pipelineboard.shared.pixel.Pixel._
import NonEmptyListOps._
import cats.data.NonEmptyList

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration.Infinite
import scala.concurrent.duration.{DurationDouble, DurationInt, FiniteDuration}
import scala.scalajs.js

final case class State(windowWidth: WPixel, ciState: ResultAndTime, drawingAreaDurationIterator: BidirectionalIterator[FiniteDuration],
                       endTime: Instant, mouseDownY: Option[Int], endTimeAtMouseDown: Instant, followTime: Boolean)

object JobCanvasImpl {
}

final class JobCanvasImpl($: BackendScope[Unit, State], timers: JsTimers, autowireApi: MockableAutowire)
                         (implicit ec: ExecutionContext) extends LazyLogging with OnUnmount {
  @SuppressWarnings(Array(Var))
  private var interval: Option[js.timers.SetIntervalHandle] = None

  def tick: CallbackTo[Unit] = $.modState{ s =>
    if (s.followTime) {
      s.copy(endTime = Instant.now)
    } else s
  } >> Callback.future {
    autowireApi.dataFeed().map { jenkinsState =>
      setJenkinsState(jenkinsState)
    }//todo show error when server is unreachable
  }

  def setJenkinsState(jenkinsState: ResultAndTime): CallbackTo[Unit] = {
    $.modState(s => {
      s.copy(ciState = jenkinsState)
    })
  }

  def adjustZoomLevel(delta: Double): CallbackTo[Unit] = {

    $.modState(s => {
      val newIterator = if (delta > 0) s.drawingAreaDurationIterator.moveRight
      else if (delta < 0) s.drawingAreaDurationIterator.moveLeft
      else s.drawingAreaDurationIterator

      s.copy(drawingAreaDurationIterator = newIterator)
    })
  }


  def adjustEndTime(delta: Int): CallbackTo[Unit] = {
    $.modState(s => {
      val timeDelta = (delta.toDouble / s.windowWidth.d) * s.drawingAreaDurationIterator.value match {
        case _: Infinite => 0.seconds
        case f: FiniteDuration => f
      }
      val validatedEnd = {
        val newEnd = s.endTimeAtMouseDown + timeDelta
        val now = Instant.now
        if (newEnd.isAfter(now)) {
          now
        } else {
          newEnd
        }
      }
      s.copy(endTime = validatedEnd, followTime = false)
    })
  }

  def start: Callback = Callback {
    interval = Some(timers.setInterval(30.seconds, {
      tick.runNow()
    })) } >> tick >> resized


  def clear: Callback = Callback {
    interval foreach js.timers.clearInterval
    interval = None
  }

  def resized: Callback = {
    $.modState(s => s.copy(windowWidth = queryJobWindowWidth().max((labelEnd + rightMargin + 100.xpx).toW)))
  }

  def render(s: State): TagOf[Element] = {
    val windowWidth = s.windowWidth
    val jobAreaWidth = (windowWidth.toX - labelEnd - rightMargin).toW
    val stripHeight = 35.hpx
    val generalMargin = 10
    import s.drawingAreaDurationIterator

    def backgroundBaseLine(idx: Int) = stripHeight.toY * idx

    val colors = NonEmptyList.of("black", "darkslategrey")

    val ciState = s.ciState.allGroups
    //todo handle if jobs are not fecthed yet
    //todo handle if data is stale

    val jobArea = JobArea(jobAreaWidth, s.endTime, drawingAreaDurationIterator.value)
    val topOfVerticalLines = backgroundBaseLine(0)

    val ArrangeResult(leftLabels, fullHeight) = RenderUtils.labels(ciState.groups, TextAnchor.End, stripHeight,
      labelEnd - generalMargin.xpx / 2)
    val ArrangeResult(rightLabels, _) = RenderUtils.labels(ciState.groups, TextAnchor.Start, stripHeight,
      windowWidth.toX - rightMargin + generalMargin.xpx / 2)

    val groupNameLabels = RenderUtils.groupNameLabels(ciState.groups, TextAnchor.End, stripHeight,
      windowWidth.toX - generalMargin.xpx)

    val leftGroupColors = RenderUtils.groupBackgrounds(ciState.groups, 0.xpx, labelEnd, stripHeight)
    val rightGroupColors = RenderUtils.groupBackgrounds(ciState.groups, windowWidth.toX - rightMargin, windowWidth.toX, stripHeight)

    val bottomOfVerticalLines = backgroundBaseLine(0) + fullHeight.toY + generalMargin.ypx
    val timestampTextY = bottomOfVerticalLines + generalMargin.ypx

    val verticleLines = moveTo(
      x = labelEnd,
      elements = verticalLines(topOfVerticalLines = topOfVerticalLines, bottomOfVerticalLines = bottomOfVerticalLines,
        timestampText = timestampTextY, jobArea, timeZone = ZoneId.systemDefault())
    )

    val uncoloredStrips = ciState.groups.toList.flatMap { case (name, group) =>
      group.jobs.map { jobDetails =>
        (color: String) => {
          val oneStrip = nestAt(
            x = labelEnd,
            elements = List(
              strip(
                jobAreaWidth = jobAreaWidth,
                stripHeight = stripHeight,
                color,
                jobRectanges(jobState = jobDetails, jobArea = jobArea, rectangleHeight = stripHeight * 0.75,
                  stripHeight = stripHeight)
              )
            )
          )
          ElementWithHeight(oneStrip, stripHeight)
        }
      }
    }
    val unpositionedStrips = uncoloredStrips.zipWithIndex.map { case (uncolored, idx) =>
      uncolored(colors.choose(idx))
    }

    val ArrangeResult(strips, _) = VerticalBoxLayout.arrange(unpositionedStrips)

    val periodText = <.text(s"${s.drawingAreaDurationIterator.value}", ^.textAnchor := textAnchorEnd, dominantBaseline := "text-before-edge")
      .x(labelEnd + jobAreaWidth.toX)
      .y(backgroundBaseLine(-1))
    val wheelListener = html_<^.^.onWheel ==> handleWheel
    val dragListeners = List(html_<^.^.onMouseDown ==> handleDown,
      html_<^.^.onMouseMove ==> handleMove,
      html_<^.^.onMouseUp ==> handleUp,
    )

    val groupedDrawObjs = <.g((strips ++ dragListeners) :+ wheelListener: _*)
    val checkboxId = "follow"
    val foreignHeight = backgroundBaseLine(1).toH
    val input = <.foreignObject(
      ^.width := 100, //single line
      ^.height := foreignHeight.d.toInt,
      //todo replace this with SVG checkbox, this is quite hard to align
      html_<^.<div(
        html_<^.< input(
          html_<^.^.id := checkboxId,
          html_<^.^.`type` := "checkbox",
          html_<^.^.checked := s.followTime,
          html_<^.^.onChange --> $.modState { s =>
            val follow = !s.followTime
            val endTime = if (follow) Instant.now else s.endTime
            s.copy(followTime = follow, endTime = endTime)
          }
        ),
        html_<^.<.label(html_<^.^.`for` := checkboxId, "Follow")
      )
    ).x(labelEnd)(anythingPos)
     .y(foreignHeight.toY * -1)(anythingPos)
    val offsetOnPageY = 50.ypx
    val svgParams = List(
      moveTo(y = offsetOnPageY, elements = List(groupedDrawObjs, verticleLines, periodText) ++
         leftGroupColors ++ rightGroupColors ++ leftLabels ++ rightLabels ++ groupNameLabels  :+ input)
    )

    html_<^.<.div(
      html_<^.<.div(
        html_<^.^.position := "absolute",
        html_<^.^.top := "10px",
        html_<^.^.right := "10px",
        html_<^.<.a(
          html_<^.^.href := "https://github.com/kszilagyi/pipeline-board",
          html_<^.^.target := "_blank",
          "About"
        )
      ),
      <.svg(
        svgParams: _*
      ).width(windowWidth)
        .height((timestampTextY + offsetOnPageY + 10.ypx).toH) //+10 to let the bottom of the text in)
    )


  }
  def handleWheel(e: SyntheticWheelEvent[SVGElement]): CallbackTo[Unit] = {
   e.stopPropagationCB >> e.preventDefaultCB >>
      adjustZoomLevel(e.deltaY)
  }

  //todo make these only work on the drawing area
  def handleDown(e: SyntheticMouseEvent[SVGElement]): CallbackTo[Unit] = {
    val x = e.clientX.toInt //this is mutable, so need to get it
    e.stopPropagationCB >> e.preventDefaultCB >> //todo enable copying of text
      Callback { org.scalajs.dom.document.body.style.cursor = "all-scroll" } >>
    $.modState(s => s.copy(mouseDownY = Some(x), endTimeAtMouseDown = s.endTime))
  }

  def handleMove(e: SyntheticMouseEvent[SVGElement]): CallbackTo[Unit] = {
    val x = e.clientX.toInt //this is mutable, so need to get it
    $.state.flatMap { s =>
      s.mouseDownY.map(_ - x) match {
        case Some(deltaX) => adjustEndTime(deltaX)
        case None => CallbackTo(())
      }
    }
  }

  def handleUp(e: SyntheticMouseEvent[SVGElement]): CallbackTo[Unit] = {
    val _ = e
    Callback { org.scalajs.dom.document.body.style.cursor = "auto" } >>
      $.modState(s => s.copy(mouseDownY = None))
  }
}
//todo I have no sense of unit: there should be different horizontal lines with different color
// which signal minutes, hours, days, weeks. months
object Canvas {

  def queryJobWindowWidth(): WPixel = {
    org.scalajs.dom.document.body.clientWidth.wpx
  }

  @SuppressWarnings(Array(Public))
  def jobCanvas(timers: JsTimers, autowire: MockableAutowire)(implicit ec: ExecutionContext) = {
    val validDurations = List(1.hour, 1.5.hours, 2.hours, 3.hours, 4.hours, 6.hours, 9.hours, 12.hours, 16.hours, 24.hours,
      36.hours, 2.days, 3.days, 5.days, 7.days, 10.days,
      14.days, 21.days, 30.days, 45.days, 60.days, 90.days, 120.days, 180.days, 270.days, 365.days).sorted

    ScalaComponent.builder[Unit]("Timer")
      .initialState(State(queryJobWindowWidth(), ResultAndTime(AllGroups(Seq.empty), Instant.now),
        drawingAreaDurationIterator = BidirectionalIterator(validDurations, 9), //:(
        Instant.now(), mouseDownY = None, endTimeAtMouseDown = Instant.now, followTime = true))
      .backend(new JobCanvasImpl(_, timers, autowire))
      .renderS(_.backend.render(_))
      .componentDidMount(_.backend.start)
      .componentWillUnmount(_.backend.clear)
      .configure(
        EventListener.install("resize", _.backend.resized, _ => org.scalajs.dom.window)
      )
      .build
  }

  implicit val ec: ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
  @SuppressWarnings(Array(Public))
  val JobCanvas = jobCanvas(RealJsTimers, new RealAutowire(new MyClient().apply[AutowireApi]))
}
